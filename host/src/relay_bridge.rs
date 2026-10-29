//! Loads the relay bridge library on demand.
//!
//! The bridge is a separate `cdylib`: a host that never relays must not pay for its TLS stack
//! or its runtime, so nothing here touches the file until `--relay-bridge` asks for it.
//!
//! The library has to outlive the thread it starts — unloading it would unmap code that is
//! still running — so the handle is kept in a static for the life of the process.

use std::ffi::CString;
use std::path::PathBuf;

use hbb_common::{
    anyhow::{bail, Context, Result},
    libloading::Library,
};

#[cfg(windows)]
const LIBRARY_NAME: &str = "relay_bridge.dll";
#[cfg(target_os = "linux")]
const LIBRARY_NAME: &str = "librelay_bridge.so";
#[cfg(target_os = "macos")]
const LIBRARY_NAME: &str = "librelay_bridge.dylib";
const ENTRY_POINT: &str = "rustdesk_relay_bridge_start";
const DEFAULT_PEER_PORT: u16 = 21118;

type StartFn = unsafe extern "C" fn(*const std::os::raw::c_char, u16) -> i32;

/// Keeps the library mapped for as long as the bridge thread runs.
static LOADED: std::sync::OnceLock<Library> = std::sync::OnceLock::new();

/// Beside the executable, which is where the build and the release archive put it.
pub fn library_path() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|dir| dir.join(LIBRARY_NAME)))
        .unwrap_or_else(|| PathBuf::from(LIBRARY_NAME))
}

/// Starts the bridge thread inside the library.
pub fn start(url: &str, peer_port: Option<u16>) -> Result<String> {
    let path = library_path();
    if !path.is_file() {
        bail!("缺少 {}（应与 rustdesk.exe 同目录）", path.display());
    }
    if LOADED.get().is_none() {
        let library =
            unsafe { Library::new(&path) }.with_context(|| format!("加载 {}", path.display()))?;
        let _ = LOADED.set(library);
    }
    let library = LOADED.get().context("bridge library vanished")?;
    // SAFETY: the symbol is looked up once per start and the string outlives the call.
    let start: hbb_common::libloading::Symbol<StartFn> =
        unsafe { library.get(ENTRY_POINT.as_bytes()) }
            .with_context(|| format!("{} 缺少 {ENTRY_POINT}", path.display()))?;
    let url = CString::new(url).context("relay url contains a NUL byte")?;
    let port = peer_port.unwrap_or(DEFAULT_PEER_PORT);
    let code = unsafe { start(url.as_ptr(), port) };
    if code != 1 {
        bail!("桥接启动失败（返回 {code}）");
    }
    Ok(format!("{} -> 127.0.0.1:{port}", url.to_string_lossy()))
}
