//! The tray menu. Its first three rows report live state, so the menu is rebuilt on every open.

use std::mem;

use base::config::keys::OPTION_RELAY_SERVER;
use hbb_common::{config::Config, log};
use winapi::{
    shared::{
        minwindef::UINT,
        windef::{HMENU, HWND, POINT},
    },
    um::winuser,
};

const ID_RESTART: UINT = 1;
const ID_QUIT: UINT = 2;

/// Rebuilds the menu from the current configuration and runs it.
pub(crate) fn show(hwnd: HWND) {
    let rows = state_rows();
    unsafe {
        let menu = winuser::CreatePopupMenu();
        if menu.is_null() {
            log::error!("tray: cannot create the menu");
            return;
        }
        for row in &rows {
            append(menu, winuser::MF_STRING | winuser::MF_GRAYED, 0, row);
        }
        append(menu, winuser::MF_SEPARATOR, 0, "");
        append(menu, winuser::MF_STRING, ID_RESTART, "重启");
        append(menu, winuser::MF_STRING, ID_QUIT, "退出");

        let mut point: POINT = mem::zeroed();
        if winuser::GetCursorPos(&mut point) == 0 {
            point.x = 0;
            point.y = 0;
        }
        // Without the foreground the menu would stay on screen after the click that opened it.
        winuser::SetForegroundWindow(hwnd);
        let choice = winuser::TrackPopupMenu(
            menu,
            winuser::TPM_RETURNCMD | winuser::TPM_NONOTIFY | winuser::TPM_RIGHTBUTTON,
            point.x,
            point.y,
            0,
            hwnd,
            std::ptr::null(),
        );
        winuser::PostMessageW(hwnd, winuser::WM_NULL, 0, 0);
        winuser::DestroyMenu(menu);

        match choice as UINT {
            ID_RESTART => restart(),
            ID_QUIT => super::window::post_quit(hwnd),
            _ => {}
        }
    }
}

fn append(menu: HMENU, flags: UINT, id: UINT, text: &str) {
    let text = super::window::wide(text);
    if unsafe { winuser::AppendMenuW(menu, flags, id as usize, text.as_ptr()) } == 0 {
        log::error!("tray: cannot add the menu row {:?}", text);
    }
}

/// Read fresh on every open: the ID, the password and the relay can all change while the host runs.
fn state_rows() -> Vec<String> {
    let id = Config::get_id();
    let password = crate::password_file::banner_text();
    let password = if password.trim().is_empty() {
        "未设置".to_owned()
    } else {
        password
    };
    let server = Config::get_rendezvous_server();
    let relay = Config::get_option(OPTION_RELAY_SERVER);
    let relay = if relay.trim().is_empty() {
        "未设置".to_owned()
    } else {
        relay
    };
    vec![
        format!("ID: {id}"),
        format!("密码: {password}"),
        format!("中继: {server}（relay: {relay}）"),
    ]
}

/// Re-launches this executable with the same arguments and ends this process.
fn restart() {
    let exe = match std::env::current_exe() {
        Ok(exe) => exe,
        Err(err) => {
            log::error!("tray: cannot locate this executable: {}", err);
            return;
        }
    };
    match std::process::Command::new(&exe)
        .args(std::env::args().skip(1))
        .spawn()
    {
        Ok(_) => std::process::exit(0),
        Err(err) => log::error!("tray: cannot restart {}: {}", exe.display(), err),
    }
}
