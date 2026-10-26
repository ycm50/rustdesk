//! Remote desktop in the terminal: the peer's frames are painted as ANSI
//! half-blocks and local key/mouse events are forwarded back.

use std::{
    thread,
    time::{Duration, Instant},
};

use base::message_proto::{ControlKey, KeyEvent, KeyboardMode};
use hbb_common::{log, rendezvous_proto::ConnType};

use crate::{
    common::input::{
        MOUSE_BUTTON_LEFT, MOUSE_BUTTON_RIGHT, MOUSE_BUTTON_WHEEL, MOUSE_TYPE_DOWN, MOUSE_TYPE_MOVE,
        MOUSE_TYPE_UP, MOUSE_TYPE_WHEEL,
    },
    terminal::{
        console::{self, Console, InputEvent, KeyEvent as ConsoleKey, MouseEvent as ConsoleMouse},
        handler::TerminalHandler,
        render,
    },
    ui_session_interface::Session,
};

const FRAME_POLL: Duration = Duration::from_millis(8);

pub fn run(id: String, password: String) -> i32 {
    // Without a console (stdout piped) there is nothing to put into raw mode
    // and no input to read; frames still render so the pane can be inspected.
    let mut term = Console::open().ok();
    if let Some(term) = term.as_mut() {
        console::install_panic_hook();
        term.install_ctrl_handler();
        if let Err(err) = term.enter_raw() {
            eprintln!("{err}");
            return 2;
        }
        term.set_title(&format!("RustDesk - {id}"));
        if let Err(err) = console::write_stdout(render::ENTER_SCREEN) {
            log::error!("cannot switch to the alternate screen: {}", err);
        }
    }

    let size = if term.is_some() {
        console::query_console_size().unwrap_or_default()
    } else {
        console::Size {
            cols: 100,
            rows: 30,
            ..Default::default()
        }
    };
    // Windows Terminal and conhost both accept 24-bit colour but neither sets
    // COLORTERM, so default to truecolor and keep an explicit opt-out for
    // terminals that cannot take it.
    let truecolor = std::env::var_os("RUSTDESK_TERM_256").is_none();
    let renderer = render::Renderer::new(
        size.cols.max(20) as usize,
        size.rows.max(6) as usize,
        truecolor,
    );

    let session: Session<TerminalHandler> = Session {
        password,
        ..Default::default()
    };
    session.set_screen(renderer);
    session
        .lc
        .write()
        .unwrap()
        .initialize(id, ConnType::DEFAULT_CONN, None, false, None, None, None);
    session.reconnect(false);

    let code = match term.as_mut() {
        Some(term) => event_loop(term, &session),
        None => passive_loop(&session),
    };
    if term.is_some() {
        let _ = console::write_stdout(render::LEAVE_SCREEN);
    }
    if let Some(term) = term.as_mut() {
        term.leave_raw();
    }
    code
}

/// No console: keep the session alive until the peer closes it.
fn passive_loop(session: &Session<TerminalHandler>) -> i32 {
    while !session.is_closed() {
        thread::sleep(Duration::from_millis(100));
    }
    0
}

fn event_loop(term: &mut Console, session: &Session<TerminalHandler>) -> i32 {
    let mut last_report = Instant::now();
    // Console mouse events only report the buttons held *now*, so the press and
    // release edges have to be derived by comparing with the previous event.
    let mut held: u8 = 0;
    loop {
        if session.is_closed() {
            return 0;
        }
        let events = match term.read_all_pending(64) {
            Ok(events) => events,
            Err(err) => {
                log::error!("console input failed: {}", err);
                return 1;
            }
        };
        for event in events {
            match event {
                InputEvent::Key(key) => {
                    if is_quit(&key) {
                        return 0;
                    }
                    if let Some(remote) = to_key_event(&key) {
                        session.send_key_event(&remote);
                    }
                }
                InputEvent::Mouse(mouse) => handle_mouse(session, &mouse, &mut held),
                InputEvent::Resize(size) => {
                    session.set_screen_size(size.cols as usize, size.rows as usize);
                }
                _ => {}
            }
        }
        if last_report.elapsed() > Duration::from_secs(60) {
            last_report = Instant::now();
            log::info!(
                "screen session: {} frames rendered",
                session.rendered_frames()
            );
        }
        thread::sleep(FRAME_POLL);
    }
}

/// Ctrl+] leaves the session, matching the shell pane.
fn is_quit(key: &ConsoleKey) -> bool {
    key.down && key.modifiers.ctrl && key.vk == 0xDD
}

fn to_key_event(key: &ConsoleKey) -> Option<KeyEvent> {
    let mut event = KeyEvent::new();
    event.down = key.down;
    event.mode = KeyboardMode::Legacy.into();
    match control_key_for(key.vk) {
        Some(control) => event.set_control_key(control),
        None => {
            let code = key.unicode as u32;
            if code == 0 {
                return None;
            }
            if code <= 0xFF {
                event.set_chr(code);
            } else {
                event.set_unicode(code);
            }
        }
    }
    crate::keyboard::client::legacy_modifiers(
        &mut event,
        key.modifiers.alt,
        key.modifiers.ctrl,
        key.modifiers.shift,
        false,
    );
    Some(event)
}

fn control_key_for(vk: u16) -> Option<ControlKey> {
    Some(match vk {
        0x08 => ControlKey::Backspace,
        0x09 => ControlKey::Tab,
        0x0D => ControlKey::Return,
        0x1B => ControlKey::Escape,
        0x10 => ControlKey::Shift,
        0x11 => ControlKey::Control,
        0x12 => ControlKey::Alt,
        0x14 => ControlKey::CapsLock,
        0x21 => ControlKey::PageUp,
        0x22 => ControlKey::PageDown,
        0x23 => ControlKey::End,
        0x24 => ControlKey::Home,
        0x25 => ControlKey::LeftArrow,
        0x26 => ControlKey::UpArrow,
        0x27 => ControlKey::RightArrow,
        0x28 => ControlKey::DownArrow,
        0x2D => ControlKey::Insert,
        0x2E => ControlKey::Delete,
        0x5B => ControlKey::Meta,
        0x5C => ControlKey::RWin,
        0x70 => ControlKey::F1,
        0x71 => ControlKey::F2,
        0x72 => ControlKey::F3,
        0x73 => ControlKey::F4,
        0x74 => ControlKey::F5,
        0x75 => ControlKey::F6,
        0x76 => ControlKey::F7,
        0x77 => ControlKey::F8,
        0x78 => ControlKey::F9,
        0x79 => ControlKey::F10,
        0x7A => ControlKey::F11,
        0x7B => ControlKey::F12,
        _ => return None,
    })
}

fn handle_mouse(session: &Session<TerminalHandler>, mouse: &ConsoleMouse, held: &mut u8) {
    let (cols, rows) = session.screen_size();
    let (width, height) = session.remote_size();
    if cols == 0 || rows == 0 || width == 0 || height == 0 {
        return;
    }
    let x = (mouse.viewport_x.max(0) as usize * width / cols.max(1)) as i32;
    let y = (mouse.viewport_y.max(0) as usize * height / rows.saturating_sub(1).max(1)) as i32;
    let (alt, ctrl, shift) = (
        mouse.modifiers.alt,
        mouse.modifiers.ctrl,
        mouse.modifiers.shift,
    );

    if mouse.wheel != 0 || mouse.horizontal_wheel != 0 {
        session.send_mouse(
            MOUSE_TYPE_WHEEL | (MOUSE_BUTTON_WHEEL << 3),
            (mouse.horizontal_wheel / 120) as i32,
            (mouse.wheel / 120) as i32,
            alt,
            ctrl,
            shift,
            false,
        );
        return;
    }

    let mut buttons = 0u8;
    if mouse.buttons.left {
        buttons |= MOUSE_BUTTON_LEFT as u8;
    }
    if mouse.buttons.right {
        buttons |= MOUSE_BUTTON_RIGHT as u8;
    }
    if mouse.buttons.middle {
        buttons |= MOUSE_BUTTON_WHEEL as u8;
    }
    let changed = buttons ^ *held;
    let (kind, mask_buttons) = if changed != 0 {
        *held = buttons;
        let kind = if buttons & changed != 0 {
            MOUSE_TYPE_DOWN
        } else {
            MOUSE_TYPE_UP
        };
        (kind, changed)
    } else if mouse.moved {
        (MOUSE_TYPE_MOVE, buttons)
    } else {
        return;
    };
    session.send_mouse(
        kind | ((mask_buttons as i32) << 3),
        x,
        y,
        alt,
        ctrl,
        shift,
        false,
    );
}
