//! The hidden window that owns the notification icon.
//!
//! A tray icon is addressed by the window handle that receives its callbacks, so the host needs a
//! window even though nothing is ever drawn.

use std::{io, mem, ptr};

use winapi::{
    shared::{
        minwindef::{LPARAM, LRESULT, UINT, WPARAM},
        windef::HWND,
    },
    um::{libloaderapi::GetModuleHandleW, winuser},
};

pub(crate) fn wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Registers the window class and creates the window that receives tray callbacks.
pub(crate) fn create(class_name: &[u16]) -> io::Result<HWND> {
    unsafe {
        let instance = GetModuleHandleW(ptr::null());
        if instance.is_null() {
            return Err(io::Error::last_os_error());
        }
        let mut class: winuser::WNDCLASSW = mem::zeroed();
        class.lpfnWndProc = Some(wnd_proc);
        class.hInstance = instance;
        class.lpszClassName = class_name.as_ptr();
        if winuser::RegisterClassW(&class) == 0 {
            return Err(io::Error::last_os_error());
        }
        let hwnd = winuser::CreateWindowExW(
            0,
            class_name.as_ptr(),
            class_name.as_ptr(),
            0,
            0,
            0,
            0,
            0,
            ptr::null_mut(),
            ptr::null_mut(),
            instance,
            ptr::null_mut(),
        );
        if hwnd.is_null() {
            return Err(io::Error::last_os_error());
        }
        Ok(hwnd)
    }
}

pub(crate) fn destroy(hwnd: HWND) {
    unsafe {
        winuser::DestroyWindow(hwnd);
    }
}

pub(crate) fn post_quit(hwnd: HWND) {
    unsafe {
        winuser::PostMessageW(hwnd, winuser::WM_CLOSE, 0, 0);
    }
}

/// Runs the message pump and returns the code the quit message carried.
pub(crate) fn message_loop() -> i32 {
    unsafe {
        let mut message: winuser::MSG = mem::zeroed();
        loop {
            let ret = winuser::GetMessageW(&mut message, ptr::null_mut(), 0, 0);
            if ret == 0 {
                return message.wParam as i32;
            }
            if ret < 0 {
                hbb_common::log::error!(
                    "tray: message loop failed: {}",
                    io::Error::last_os_error()
                );
                return 1;
            }
            winuser::TranslateMessage(&message);
            winuser::DispatchMessageW(&message);
        }
    }
}

unsafe extern "system" fn wnd_proc(
    hwnd: HWND,
    message: UINT,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        crate::tray::WM_TRAY => {
            // The shell reports the mouse event itself; every click shape opens the same menu.
            let event = lparam as u32;
            if event == winuser::WM_LBUTTONUP
                || event == winuser::WM_RBUTTONUP
                || event == winuser::WM_CONTEXTMENU
            {
                super::menu::show(hwnd);
            }
            0
        }
        winuser::WM_CLOSE => {
            winuser::PostQuitMessage(0);
            0
        }
        _ => winuser::DefWindowProcW(hwnd, message, wparam, lparam),
    }
}
