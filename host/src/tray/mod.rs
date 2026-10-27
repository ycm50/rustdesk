//! Windows system tray: the resident background face of a host started without `--headless`.
//!
//! This build has no GUI, so the tray is the only place an operator can read the device ID, the
//! password in force and the relay server, or restart/quit the host without a console.

#[cfg(windows)]
use std::thread;

#[cfg(windows)]
pub(crate) const CLASS_NAME: &str = "RustDeskHostTray";
#[cfg(windows)]
pub(crate) const WM_TRAY: u32 = winapi::um::winuser::WM_APP + 1;
#[cfg(windows)]
const TOOLTIP: &str = "RustDesk 被控端";

/// Parks the process in the notification area.
///
/// `server_started` mirrors `status::run_host`: an argument-less run reaches here after
/// `core_main` already spawned the server, and starting a second one loses the IPC pipe.
#[cfg(windows)]
pub fn run(server_started: bool) -> i32 {
    // Nothing about a resident host belongs in a terminal: no console window, no log echo.
    console::detach();
    if !server_started {
        thread::spawn(|| crate::start_server(false, false));
    }

    let class_name = window::wide(CLASS_NAME);
    let hwnd = match window::create(&class_name) {
        Ok(hwnd) => hwnd,
        Err(err) => {
            hbb_common::log::error!("tray: cannot create the host window: {}", err);
            return headless_fallback();
        }
    };
    let icon = match icon::app_icon() {
        Some(icon) => icon,
        None => {
            hbb_common::log::error!("tray: no application icon available");
            window::destroy(hwnd);
            return headless_fallback();
        }
    };
    if let Err(err) = icon::add(hwnd, icon, TOOLTIP) {
        // The shell refuses the icon in a session without a notification area. Exiting here
        // would leave an unreachable host, so keep serving exactly like --headless.
        hbb_common::log::error!(
            "tray: the shell refused the notification icon ({}); running headless",
            err
        );
        window::destroy(hwnd);
        return headless_fallback();
    }

    let code = window::message_loop();

    // A process that disappears leaves a ghost icon in the area until the shell is told it went.
    icon::remove(hwnd);
    window::destroy(hwnd);
    code
}

/// Serves without a notification area, like `--headless`, but without starting a second
/// server: `run` already spawned one, and a duplicate start loses the IPC pipe and exits -1.
#[cfg(windows)]
fn headless_fallback() -> i32 {
    crate::terminal::status::run_host(true, false)
}

#[cfg(windows)]
pub(crate) mod console;
#[cfg(windows)]
mod icon;
#[cfg(windows)]
mod menu;
#[cfg(windows)]
mod window;

/// Without a notification area there is nothing to attach to; the caller must not expect a tray.
#[cfg(not(windows))]
pub fn run(_server_started: bool) -> i32 {
    2
}
