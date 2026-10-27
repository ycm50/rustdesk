//! Hides the terminal in tray mode.
//!
//! A double-click launch gives the process its own console window, and a launch from a shell
//! leaves every log line there. Neither belongs to a resident background host, so the console is
//! detached as soon as the tray path is chosen.

use std::ffi::c_void;

const STD_OUTPUT_HANDLE: u32 = (-11i32) as u32;
const STD_ERROR_HANDLE: u32 = (-12i32) as u32;

#[link(name = "kernel32")]
extern "system" {
    fn GetConsoleWindow() -> isize;
    fn FreeConsole() -> i32;
    fn SetStdHandle(n_std_handle: u32, handle: *mut c_void) -> i32;
}

/// Points stdout/stderr at NUL, then detaches from the console.
///
/// The redirection comes first on purpose: `println!` aborts the process when its write fails,
/// and the standard handles stop being writable the moment the console is gone.
pub(crate) fn detach() {
    redirect_std_to_nul();
    // Also closes the window Windows created for a double-click launch. When the host was
    // started from an existing shell this only detaches, leaving that shell's window alone.
    unsafe {
        if GetConsoleWindow() != 0 {
            FreeConsole();
        }
    }
}

fn redirect_std_to_nul() {
    use std::os::windows::io::AsRawHandle;

    let Ok(out) = std::fs::OpenOptions::new().write(true).open("NUL") else {
        return;
    };
    let Ok(err) = std::fs::OpenOptions::new().write(true).open("NUL") else {
        return;
    };
    unsafe {
        SetStdHandle(STD_OUTPUT_HANDLE, out.as_raw_handle() as *mut c_void);
        SetStdHandle(STD_ERROR_HANDLE, err.as_raw_handle() as *mut c_void);
    }
    // The handles have to stay open for the life of the process, so the owners are leaked
    // rather than dropped.
    std::mem::forget(out);
    std::mem::forget(err);
}
