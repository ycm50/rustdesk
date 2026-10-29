#[cfg(target_os = "windows")]
fn build_c_impl() {
    let mut build = cc::Build::new();

    build.file("src/windows/wf_cliprdr.c");

    {
        build.flag_if_supported("-Wno-c++0x-extensions");
        build.flag_if_supported("-Wno-return-type-c-linkage");
        build.flag_if_supported("-Wno-invalid-offsetof");
        build.flag_if_supported("-Wno-unused-parameter");
        // GCC 14+ promotes -Wint-conversion to an error by default; this vendored C code
        // passes pointers through PostMessage's LPARAM (as the Windows SDK allows).
        build.flag_if_supported("-Wno-error=int-conversion");

        if build.get_compiler().is_like_msvc() {
            build.define("WIN32", "");
            // build.define("_AMD64_", "");
            build.flag("-Z7");
            build.flag("-GR-");
            // build.flag("-std:c++11");
        } else {
            // clang targeting *-pc-windows-msvc rejects -fPIC.
            if !std::env::var("TARGET").unwrap_or_default().contains("msvc") {
                build.flag("-fPIC");
            }
            // build.flag("-std=c++11");
            // build.flag("-include");
            // build.flag(&confdefs_path.to_string_lossy());
        }

        build.compile("mycliprdr");
    }

    println!("cargo:rerun-if-changed=src/windows/wf_cliprdr.c");
}

fn main() {
    #[cfg(target_os = "windows")]
    build_c_impl();
}
