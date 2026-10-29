#[cfg(windows)]
fn build_windows() {
    // Building a non-Windows target on a Windows host must not compile the Windows shim.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let file = "src/platform/windows.cc";
    let file2 = "src/platform/windows_delete_test_cert.cc";
    let mut build = cc::Build::new();
    build.cpp(true).std("c++17").define("RUSTDESK_SCITER", None);
    // windows_delete_test_cert.cc initialises a const char[] with the byte values of an
    // embedded certificate; MSVC accepts the >127 literals, GCC's C++ front end rejects them
    // as narrowing. The 2^8 values are intentional.
    build.flag_if_supported("-Wno-narrowing");
    build.file(file).file(file2).compile("windows");
    println!("cargo:rustc-link-lib=WtsApi32");
    // MSVC headers pull these in with #pragma comment(lib, ...); MinGW has no such
    // auto-link, so the COM GUIDs used by libs/clipboard and scrap's libyuv binding
    // stay undefined. Passed as link-args (not link-lib) so they are appended after
    // the rlibs that reference them.
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("gnu") {
        for lib in ["uuid", "windowscodecs", "ole32", "oleaut32", "comdlg32"] {
            println!("cargo:rustc-link-arg=-l{lib}");
        }
        build_sas_import_lib();
    }
    println!("cargo:rerun-if-changed={}", file);
    println!("cargo:rerun-if-changed={}", file2);
}

/// mingw-w64 ships no sas.lib/libsas.a, but src/platform/windows.rs links "sas" for SendSAS.
/// MSVC gets it from the Windows SDK; for GNU targets generate the import library from the
/// committed .def with dlltool (part of the binutils that cc already requires) and put it on
/// the link search path, so the tree is buildable without any hand-made file in the MSYS2 prefix.
#[cfg(windows)]
fn build_sas_import_lib() {
    let out_dir = std::path::PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR is set"));
    let def = std::path::Path::new("libs/win-stubs/sas.def");
    println!("cargo:rerun-if-changed={}", def.display());
    let lib = out_dir.join("libsas.a");
    let status = std::process::Command::new("dlltool")
        .arg("-d")
        .arg(def)
        .arg("-l")
        .arg(&lib)
        .arg("-k")
        .status();
    match status {
        Ok(s) if s.success() => {}
        Ok(s) => panic!("dlltool failed to build libsas.a from {}: {s}", def.display()),
        Err(e) => panic!(
            "cannot run dlltool to generate libsas.a from {} (needed because mingw-w64 ships no \
             sas import library): {e}. Install binutils, e.g. 'pacman -S mingw-w64-ucrt-x86_64-binutils'.",
            def.display()
        ),
    }
    println!("cargo:rustc-link-search=native={}", out_dir.display());
}

#[cfg(target_os = "macos")]
fn build_mac() {
    let file = "src/platform/macos.mm";
    let mut b = cc::Build::new();
    if let Ok(os_version::OsVersion::MacOS(v)) = os_version::detect() {
        let v = v.version;
        if v.contains("10.14") {
            b.flag("-DNO_InputMonitoringAuthStatus=1");
        }
    }
    b.flag("-std=c++17").file(file).compile("macos");
    println!("cargo:rerun-if-changed={}", file);
}

#[cfg(all(windows, feature = "inline"))]
fn build_manifest() {
    use std::io::Write;
    if std::env::var("PROFILE").unwrap() == "release" {
        let mut res = winres::WindowsResource::new();
        res.set_icon("res/icon.ico")
            .set_language(winapi::um::winnt::MAKELANGID(
                winapi::um::winnt::LANG_ENGLISH,
                winapi::um::winnt::SUBLANG_ENGLISH_US,
            ))
            .set_manifest_file("res/manifest.xml");
        match res.compile() {
            Err(e) => {
                write!(std::io::stderr(), "{}", e).unwrap();
                std::process::exit(1);
            }
            Ok(_) => {}
        }
    }
}

// bionic only exports getifaddrs()/freeifaddrs() from API 24, while the jniLibs
// are built against the API 21 sysroot (flutter/ndk_*.sh). webrtc-util calls
// them, so without this the android link fails on undefined symbols.
fn build_android_ifaddrs() {
    let file = "src/platform/android_ifaddrs.c";
    cc::Build::new().file(file).compile("android_ifaddrs");
    println!("cargo:rerun-if-changed={}", file);
}

fn install_android_deps() {
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap();
    if target_os != "android" {
        return;
    }
    let mut target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap();
    if target_arch == "x86_64" {
        target_arch = "x64".to_owned();
    } else if target_arch == "x86" {
        target_arch = "x86".to_owned();
    } else if target_arch == "aarch64" {
        target_arch = "arm64".to_owned();
    } else {
        target_arch = "arm".to_owned();
    }
    let target = format!("{}-android", target_arch);
    let vcpkg_root = std::env::var("VCPKG_ROOT").unwrap();
    let mut path: std::path::PathBuf = vcpkg_root.into();
    if let Ok(vcpkg_root) = std::env::var("VCPKG_INSTALLED_ROOT") {
        path = vcpkg_root.into();
    } else {
        path.push("installed");
    }
    path.push(target);
    println!(
        "cargo:rustc-link-search={}",
        path.join("lib").to_str().unwrap()
    );
    println!("cargo:rustc-link-lib=ndk_compat");
    println!("cargo:rustc-link-lib=c++");
    println!("cargo:rustc-link-lib=OpenSLES");
}

fn main() {
    hbb_common::gen_version();
    install_android_deps();
    #[cfg(all(windows, feature = "inline"))]
    build_manifest();
    #[cfg(windows)]
    build_windows();
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap();
    if target_os == "macos" {
        #[cfg(target_os = "macos")]
        build_mac();
        println!("cargo:rustc-link-lib=framework=ApplicationServices");
    }
    if target_os == "android" {
        build_android_ifaddrs();
    }
    println!("cargo:rerun-if-changed=build.rs");
}
