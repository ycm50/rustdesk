// A GUI-subsystem binary is started without a console, so a double-click never opens a terminal
// window. The command-line paths borrow the launching shell's console in
// `platform::attach_parent_console()`; see `host/TERMINAL.md`.
#![cfg_attr(windows, windows_subsystem = "windows")]

use librustdesk::*;

#[cfg(any(target_os = "android", target_os = "ios", feature = "flutter"))]
fn main() {
    if !common::global_init() {
        eprintln!("Global initialization failed.");
        return;
    }
    common::test_rendezvous_server();
    common::test_nat_type();
    common::global_clean();
}

#[cfg(not(any(target_os = "android", target_os = "ios", feature = "flutter")))]
fn main() -> std::process::ExitCode {
    // First, before anything can print: this build has no console of its own.
    #[cfg(windows)]
    platform::attach_parent_console();
    #[cfg(windows)]
    if let Some(exit_code) = platform::handle_service_control() {
        return exit_code;
    }
    #[cfg(all(windows, not(feature = "inline")))]
    unsafe {
        winapi::um::shellscalingapi::SetProcessDpiAwareness(2);
    }
    // Preserve command failures, notably verified macOS offline update failures,
    // as a non-zero process exit status for CLI callers.
    let exit_code = match crate::core_main::core_main() {
        Some(crate::core_main::CoreMainAction::StartUi(mut args)) => {
            std::process::ExitCode::from(crate::terminal::run(&mut args) as u8)
        }
        Some(crate::core_main::CoreMainAction::ExitFailure(err)) => {
            eprintln!("Command failed: {err}");
            hbb_common::log::error!("Command failed: {err}");
            std::process::ExitCode::FAILURE
        }
        None => std::process::ExitCode::SUCCESS,
    };
    common::global_clean();
    exit_code
}
