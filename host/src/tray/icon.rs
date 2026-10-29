//! The notification-area icon itself.

use std::{io, mem, ptr};

use hbb_common::log;
use winapi::{
    shared::windef::{HICON, HWND},
    um::{shellapi, winuser},
};

/// The stock icon comes from the system module, which keeps this crate free of resource files.
pub(crate) fn app_icon() -> Option<HICON> {
    unsafe {
        let icon = winuser::LoadIconW(ptr::null_mut(), winuser::IDI_APPLICATION);
        if icon.is_null() {
            None
        } else {
            Some(icon)
        }
    }
}

pub(crate) fn add(hwnd: HWND, icon: HICON, tip: &str) -> io::Result<()> {
    let mut data = notify_data(hwnd);
    data.uFlags = shellapi::NIF_ICON | shellapi::NIF_MESSAGE | shellapi::NIF_TIP;
    data.uCallbackMessage = crate::tray::WM_TRAY;
    data.hIcon = icon;
    let tip = super::window::wide(tip);
    let len = tip.len().min(data.szTip.len());
    data.szTip[..len].copy_from_slice(&tip[..len]);
    if unsafe { shellapi::Shell_NotifyIconW(shellapi::NIM_ADD, &mut data) } != 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

pub(crate) fn remove(hwnd: HWND) {
    let mut data = notify_data(hwnd);
    if unsafe { shellapi::Shell_NotifyIconW(shellapi::NIM_DELETE, &mut data) } == 0 {
        log::warn!("tray: the shell did not remove the notification icon");
    }
}

fn notify_data(hwnd: HWND) -> shellapi::NOTIFYICONDATAW {
    let mut data: shellapi::NOTIFYICONDATAW = unsafe { mem::zeroed() };
    data.cbSize = mem::size_of::<shellapi::NOTIFYICONDATAW>() as u32;
    data.hWnd = hwnd;
    data.uID = 1;
    data
}
