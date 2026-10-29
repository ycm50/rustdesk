//! Windows console backend for the terminal TUI prototype.
//!
//! Design goals (deliberately boring, so it can be lifted into RustDesk):
//! * no third-party crates; only `std` plus hand-written `kernel32` FFI;
//! * every expected failure is a `Result`, never an `unwrap`;
//! * raw mode is restored on normal exit, on `?`, and by a panic hook;
//! * input is read with `ReadConsoleInputW`, not `ReadFile`, so we get
//!   virtual-key codes, modifiers, mouse buttons and wheel deltas directly.
//!
//! This module is Windows-only.

#![cfg(windows)]
#![allow(non_snake_case, non_camel_case_types, dead_code)]

use std::ffi::c_void;
use std::fmt;
use std::io;
use std::mem::size_of;
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicIsize, AtomicU32, Ordering};

// ---------------------------------------------------------------------------
// Win32 types / constants (kept intentionally close to the SDK names)
// ---------------------------------------------------------------------------

pub type HANDLE = *mut c_void;
pub type DWORD = u32;
type BOOL = i32;
type WORD = u16;
type SHORT = i16;

const INVALID_HANDLE_VALUE: HANDLE = -1isize as HANDLE;

const STD_INPUT_HANDLE: DWORD = -10i32 as DWORD;
const STD_OUTPUT_HANDLE: DWORD = -11i32 as DWORD;
const STD_ERROR_HANDLE: DWORD = -12i32 as DWORD;

const ENABLE_PROCESSED_INPUT: DWORD = 0x0001;
const ENABLE_LINE_INPUT: DWORD = 0x0002;
const ENABLE_ECHO_INPUT: DWORD = 0x0004;
const ENABLE_WINDOW_INPUT: DWORD = 0x0008;
const ENABLE_MOUSE_INPUT: DWORD = 0x0010;
const ENABLE_QUICK_EDIT_MODE: DWORD = 0x0040;
const ENABLE_EXTENDED_FLAGS: DWORD = 0x0080;
const ENABLE_VIRTUAL_TERMINAL_INPUT: DWORD = 0x0200;

const ENABLE_PROCESSED_OUTPUT: DWORD = 0x0001;
const ENABLE_VIRTUAL_TERMINAL_PROCESSING: DWORD = 0x0004;

const KEY_EVENT: WORD = 0x0001;
const MOUSE_EVENT: WORD = 0x0002;
const WINDOW_BUFFER_SIZE_EVENT: WORD = 0x0004;
const MENU_EVENT: WORD = 0x0008;
const FOCUS_EVENT: WORD = 0x0010;

const FROM_LEFT_1ST_BUTTON_PRESSED: DWORD = 0x0001;
const RIGHTMOST_BUTTON_PRESSED: DWORD = 0x0002;
const FROM_LEFT_2ND_BUTTON_PRESSED: DWORD = 0x0004;
const FROM_LEFT_3RD_BUTTON_PRESSED: DWORD = 0x0008;
const FROM_LEFT_4TH_BUTTON_PRESSED: DWORD = 0x0010;

const MOUSE_MOVED: DWORD = 0x0001;
const DOUBLE_CLICK: DWORD = 0x0002;
const MOUSE_WHEELED: DWORD = 0x0004;
const MOUSE_HWHEELED: DWORD = 0x0008;

// Public copies for callers that synthesize INPUT_RECORDs in tests.
pub const MOUSE_EVENT_MOVED: u32 = MOUSE_MOVED;
pub const MOUSE_EVENT_DOUBLE_CLICK: u32 = DOUBLE_CLICK;
pub const MOUSE_EVENT_WHEELED: u32 = MOUSE_WHEELED;
pub const MOUSE_EVENT_HWHEELED: u32 = MOUSE_HWHEELED;
pub const MOUSE_BUTTON_LEFT: u32 = FROM_LEFT_1ST_BUTTON_PRESSED;

const SHIFT_PRESSED: DWORD = 0x0010;
const LEFT_ALT_PRESSED: DWORD = 0x0002;
const LEFT_CTRL_PRESSED: DWORD = 0x0008;
const RIGHT_ALT_PRESSED: DWORD = 0x0001;
const RIGHT_CTRL_PRESSED: DWORD = 0x0004;
const ENHANCED_KEY: DWORD = 0x0100;
const CAPSLOCK_ON: DWORD = 0x0080;
const NUMLOCK_ON: DWORD = 0x0020;
const SCROLLLOCK_ON: DWORD = 0x0040;

const WAIT_OBJECT_0: DWORD = 0;
const WAIT_TIMEOUT: DWORD = 0x00000102;
const WAIT_FAILED: DWORD = 0xFFFFFFFF;

const CTRL_C_EVENT: DWORD = 0;
const CTRL_BREAK_EVENT: DWORD = 1;
const CTRL_CLOSE_EVENT: DWORD = 2;
const CTRL_LOGOFF_EVENT: DWORD = 5;
const CTRL_SHUTDOWN_EVENT: DWORD = 6;

pub const VK_BACK: u16 = 0x08;
pub const VK_TAB: u16 = 0x09;
pub const VK_RETURN: u16 = 0x0D;
pub const VK_SHIFT: u16 = 0x10;
pub const VK_CONTROL: u16 = 0x11;
pub const VK_MENU: u16 = 0x12;
pub const VK_PAUSE: u16 = 0x13;
pub const VK_CAPITAL: u16 = 0x14;
pub const VK_ESCAPE: u16 = 0x1B;
pub const VK_SPACE: u16 = 0x20;
pub const VK_PRIOR: u16 = 0x21;
pub const VK_NEXT: u16 = 0x22;
pub const VK_END: u16 = 0x23;
pub const VK_HOME: u16 = 0x24;
pub const VK_LEFT: u16 = 0x25;
pub const VK_UP: u16 = 0x26;
pub const VK_RIGHT: u16 = 0x27;
pub const VK_DOWN: u16 = 0x28;
pub const VK_INSERT: u16 = 0x2D;
pub const VK_DELETE: u16 = 0x2E;
pub const VK_A: u16 = 0x41;
pub const VK_C: u16 = 0x43;
pub const VK_Z: u16 = 0x5A;
pub const VK_F1: u16 = 0x70;
pub const VK_F12: u16 = 0x7B;
pub const VK_OEM_4: u16 = 0xDB; // [ { on a US layout
pub const VK_OEM_5: u16 = 0xDC; // \ |
pub const VK_OEM_6: u16 = 0xDD; // ] }

// ---------------------------------------------------------------------------
// FFI structures
// ---------------------------------------------------------------------------

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct COORD {
    pub X: SHORT,
    pub Y: SHORT,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct SMALL_RECT {
    pub Left: SHORT,
    pub Top: SHORT,
    pub Right: SHORT,
    pub Bottom: SHORT,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct CONSOLE_SCREEN_BUFFER_INFO {
    pub dwSize: COORD,
    pub dwCursorPosition: COORD,
    pub wAttributes: WORD,
    pub srWindow: SMALL_RECT,
    pub dwMaximumWindowSize: COORD,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union KEY_EVENT_CHAR {
    pub UnicodeChar: u16,
    pub AsciiChar: u8,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct KEY_EVENT_RECORD {
    pub bKeyDown: BOOL,
    pub wRepeatCount: WORD,
    pub wVirtualKeyCode: WORD,
    pub wVirtualScanCode: WORD,
    pub uChar: KEY_EVENT_CHAR,
    pub dwControlKeyState: DWORD,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MOUSE_EVENT_RECORD {
    pub dwMousePosition: COORD,
    pub dwButtonState: DWORD,
    pub dwControlKeyState: DWORD,
    pub dwEventFlags: DWORD,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct WINDOW_BUFFER_SIZE_RECORD {
    pub dwSize: COORD,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct FOCUS_EVENT_RECORD {
    pub bSetFocus: BOOL,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct MENU_EVENT_RECORD {
    pub dwCommandId: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub union INPUT_RECORD_UNION {
    pub KeyEvent: KEY_EVENT_RECORD,
    pub MouseEvent: MOUSE_EVENT_RECORD,
    pub WindowBufferSizeEvent: WINDOW_BUFFER_SIZE_RECORD,
    pub MenuEvent: MENU_EVENT_RECORD,
    pub FocusEvent: FOCUS_EVENT_RECORD,
    pub Padding: [u8; 16],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct INPUT_RECORD {
    pub EventType: WORD,
    pub Event: INPUT_RECORD_UNION,
}

impl Default for INPUT_RECORD {
    fn default() -> Self {
        Self {
            EventType: 0,
            Event: INPUT_RECORD_UNION { Padding: [0; 16] },
        }
    }
}

// These sizes are part of the ABI.  If they ever change, fail loudly at
// compile time instead of corrupting the input buffer.
const _: () = assert!(size_of::<KEY_EVENT_RECORD>() == 16);
const _: () = assert!(size_of::<MOUSE_EVENT_RECORD>() == 16);
const _: () = assert!(size_of::<INPUT_RECORD>() == 20);
const _: () = assert!(size_of::<CONSOLE_SCREEN_BUFFER_INFO>() == 22);

// ---------------------------------------------------------------------------
// FFI imports
// ---------------------------------------------------------------------------

#[link(name = "kernel32")]
extern "system" {
    fn GetStdHandle(nStdHandle: DWORD) -> HANDLE;
    fn GetConsoleMode(hConsoleHandle: HANDLE, lpMode: *mut DWORD) -> BOOL;
    fn SetConsoleMode(hConsoleHandle: HANDLE, dwMode: DWORD) -> BOOL;
    fn GetConsoleScreenBufferInfo(
        hConsoleOutput: HANDLE,
        lpConsoleScreenBufferInfo: *mut CONSOLE_SCREEN_BUFFER_INFO,
    ) -> BOOL;
    fn ReadConsoleInputW(
        hConsoleInput: HANDLE,
        lpBuffer: *mut INPUT_RECORD,
        nLength: DWORD,
        lpNumberOfEventsRead: *mut DWORD,
    ) -> BOOL;
    fn WriteConsoleInputW(
        hConsoleInput: HANDLE,
        lpBuffer: *const INPUT_RECORD,
        nLength: DWORD,
        lpNumberOfEventsWritten: *mut DWORD,
    ) -> BOOL;
    fn GetNumberOfConsoleInputEvents(hConsoleInput: HANDLE, lpNumberOfEvents: *mut DWORD) -> BOOL;
    fn FlushConsoleInputBuffer(hConsoleInput: HANDLE) -> BOOL;
    fn WaitForSingleObject(hHandle: HANDLE, dwMilliseconds: DWORD) -> DWORD;
    fn SetConsoleOutputCP(wCodePageID: u32) -> BOOL;
    fn SetConsoleCP(wCodePageID: u32) -> BOOL;
    fn GetConsoleOutputCP() -> u32;
    fn GetConsoleCP() -> u32;
    fn GetConsoleTitleW(lpConsoleTitle: *mut u16, nSize: DWORD) -> DWORD;
    fn SetConsoleTitleW(lpConsoleTitle: *const u16) -> BOOL;
    fn SetConsoleCtrlHandler(
        HandlerRoutine: Option<unsafe extern "system" fn(DWORD) -> BOOL>,
        Add: BOOL,
    ) -> BOOL;
    fn WriteFile(
        hFile: HANDLE,
        lpBuffer: *const c_void,
        nNumberOfBytesToWrite: DWORD,
        lpNumberOfBytesWritten: *mut DWORD,
        lpOverlapped: *mut c_void,
    ) -> BOOL;
    fn ReadFile(
        hFile: HANDLE,
        lpBuffer: *mut c_void,
        nNumberOfBytesToRead: DWORD,
        lpNumberOfBytesRead: *mut DWORD,
        lpOverlapped: *mut c_void,
    ) -> BOOL;
}

// ---------------------------------------------------------------------------
// Error type
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct ConsoleError {
    pub op: &'static str,
    pub code: u32,
    pub message: String,
}

impl ConsoleError {
    fn last(op: &'static str) -> Self {
        let err = io::Error::last_os_error();
        Self {
            op,
            code: err.raw_os_error().unwrap_or(0) as u32,
            message: err.to_string(),
        }
    }

    fn custom(op: &'static str, message: impl Into<String>) -> Self {
        Self {
            op,
            code: 0,
            message: message.into(),
        }
    }

    pub fn is_not_a_console(&self) -> bool {
        // ERROR_INVALID_HANDLE (6) is what GetConsoleMode returns for pipes.
        self.code == 6
    }
}

impl fmt::Display for ConsoleError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.code == 0 {
            write!(f, "{}: {}", self.op, self.message)
        } else {
            write!(f, "{} failed (Win32 error {}): {}", self.op, self.code, self.message)
        }
    }
}

impl std::error::Error for ConsoleError {}

// ---------------------------------------------------------------------------
// Public event model
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Size {
    /// Visible viewport width in character cells.
    pub cols: u16,
    /// Visible viewport height in character cells.
    pub rows: u16,
    /// Scrollback buffer width (`dwSize.X`), usually == cols.
    pub buffer_cols: u16,
    /// Scrollback buffer height (`dwSize.Y`); NOT the visible height.
    pub buffer_rows: u16,
    /// Viewport origin inside the buffer (for mouse-coordinate conversion).
    pub left: i16,
    pub top: i16,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct KeyModifiers {
    pub ctrl: bool,
    pub alt: bool,
    pub shift: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Direction {
    Up,
    Down,
    Left,
    Right,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyCode {
    /// A printable character.  For Ctrl+letter see `CtrlLetter`.
    Char(char),
    /// Ctrl+A .. Ctrl+Z.  The byte is lowercase (`b'a'` .. `b'z'`).
    CtrlLetter(u8),
    /// Ctrl+[ \ ] ^ _ and other control characters that are not letters.
    CtrlChar(char),
    Function(u8),
    Arrow(Direction),
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    Escape,
    Enter,
    Tab,
    Backspace,
    Space,
    Unknown(u16),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct KeyEvent {
    pub vk: u16,
    pub scan_code: u16,
    /// UTF-16 code unit as delivered by the console.  Kept for RustDesk's
    /// `unicode` field; use `ch` for the decoded scalar value.
    pub unicode: u16,
    /// Decoded Unicode scalar.  `None` for pure function keys, dead keys,
    /// and while a surrogate pair is waiting for its low half.
    pub ch: Option<char>,
    pub down: bool,
    pub repeat: u16,
    pub modifiers: KeyModifiers,
    pub enhanced: bool,
    pub caps_lock: bool,
    pub num_lock: bool,
    pub scroll_lock: bool,
}

impl KeyEvent {
    pub fn ctrl_letter(&self) -> Option<u8> {
        if self.modifiers.ctrl
            && (0x01..=0x1A).contains(&self.unicode)
            && (VK_A..=VK_Z).contains(&self.vk)
        {
            Some((self.unicode as u8) + b'a' - 1)
        } else {
            None
        }
    }

    pub fn code(&self) -> KeyCode {
        if let Some(letter) = self.ctrl_letter() {
            return KeyCode::CtrlLetter(letter);
        }
        match self.vk {
            VK_BACK => KeyCode::Backspace,
            VK_TAB => KeyCode::Tab,
            VK_RETURN => KeyCode::Enter,
            VK_ESCAPE => KeyCode::Escape,
            VK_SPACE => KeyCode::Space,
            VK_PRIOR => KeyCode::PageUp,
            VK_NEXT => KeyCode::PageDown,
            VK_END => KeyCode::End,
            VK_HOME => KeyCode::Home,
            VK_LEFT => KeyCode::Arrow(Direction::Left),
            VK_UP => KeyCode::Arrow(Direction::Up),
            VK_RIGHT => KeyCode::Arrow(Direction::Right),
            VK_DOWN => KeyCode::Arrow(Direction::Down),
            VK_INSERT => KeyCode::Insert,
            VK_DELETE => KeyCode::Delete,
            VK_F1..=VK_F12 => KeyCode::Function((self.vk - VK_F1 + 1) as u8),
            VK_OEM_4 if self.modifiers.ctrl => KeyCode::CtrlChar('['),
            VK_OEM_5 if self.modifiers.ctrl => KeyCode::CtrlChar('\\'),
            VK_OEM_6 if self.modifiers.ctrl => KeyCode::CtrlChar(']'),
            _ => {
                if self.modifiers.ctrl {
                    // Ctrl+^ / Ctrl+_ / Ctrl+\ can arrive with non-OEM VKs on
                    // some layouts; use the control code as the fallback.
                    if let Some(c) = ctrl_char_from_code(self.unicode) {
                        return KeyCode::CtrlChar(c);
                    }
                }
                match self.ch {
                    Some(c) if !c.is_control() => KeyCode::Char(c),
                    _ => KeyCode::Unknown(self.vk),
                }
            }
        }
    }

    pub fn is_ctrl_c(&self) -> bool {
        self.modifiers.ctrl && (self.unicode == 0x03 || self.vk == VK_C)
    }
}

fn ctrl_char_from_code(code: u16) -> Option<char> {
    match code {
        0x1B => Some('['),
        0x1C => Some('\\'),
        0x1D => Some(']'),
        0x1E => Some('^'),
        0x1F => Some('_'),
        _ => None,
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MouseButtons {
    pub left: bool,
    pub right: bool,
    pub middle: bool,
    pub x1: bool,
    pub x2: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MouseEvent {
    /// Raw console-buffer coordinate, exactly what ReadConsoleInputW returns.
    pub x: i16,
    pub y: i16,
    /// `x/y` minus the viewport origin.  This is the coordinate a TUI wants.
    pub viewport_x: i16,
    pub viewport_y: i16,
    pub buttons: MouseButtons,
    /// Raw wheel delta: +/-120 per notch on a standard mouse.  Keep the raw
    /// value; a precision touchpad can send smaller deltas.
    pub wheel: i16,
    pub horizontal_wheel: i16,
    pub moved: bool,
    pub double_click: bool,
    pub modifiers: KeyModifiers,
    pub raw_button_state: u32,
    pub raw_event_flags: u32,
}

impl MouseEvent {
    pub fn wheel_notches(&self) -> f32 {
        self.wheel as f32 / 120.0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InputEvent {
    Key(KeyEvent),
    Mouse(MouseEvent),
    Resize(Size),
    Focus(bool),
    Menu(u32),
    Unknown(u16),
}

// ---------------------------------------------------------------------------
// Panic / Ctrl+C emergency restoration
// ---------------------------------------------------------------------------

static RAW_ACTIVE: AtomicBool = AtomicBool::new(false);
static ORIG_IN_MODE: AtomicU32 = AtomicU32::new(0);
static ORIG_OUT_MODE: AtomicU32 = AtomicU32::new(0);
static ORIG_IN_CP: AtomicU32 = AtomicU32::new(0);
static ORIG_OUT_CP: AtomicU32 = AtomicU32::new(0);
static IN_HANDLE: AtomicIsize = AtomicIsize::new(0);
static OUT_HANDLE: AtomicIsize = AtomicIsize::new(0);
static PANIC_HOOK_INSTALLED: AtomicBool = AtomicBool::new(false);

/// Best-effort restoration that is safe to call from a panic hook or a
/// console-control handler.  It never allocates and never panics.
pub fn emergency_restore() {
    if !RAW_ACTIVE.swap(false, Ordering::SeqCst) {
        return;
    }
    let in_h = IN_HANDLE.load(Ordering::SeqCst) as HANDLE;
    let out_h = OUT_HANDLE.load(Ordering::SeqCst) as HANDLE;

    // Leave the alternate screen and make the cursor visible while the
    // output handle still has VT processing enabled.  Write to stdout, NOT
    // stderr: VT processing is a per-handle console mode, and stderr usually
    // does not have ENABLE_VIRTUAL_TERMINAL_PROCESSING.  Writing the escape
    // bytes to stderr prints them literally (a classic panic-path bug).
    let cleanup = b"\x1b[0m\x1b[?1006l\x1b[?1000l\x1b[?25h\x1b[?1049l";
    if !out_h.is_null() && out_h != INVALID_HANDLE_VALUE {
        write_handle_bytes(out_h, cleanup);
    } else {
        write_stderr_bytes(cleanup);
    }

    if !out_h.is_null() && out_h != INVALID_HANDLE_VALUE {
        let _ = unsafe { SetConsoleMode(out_h, ORIG_OUT_MODE.load(Ordering::SeqCst)) };
        let cp = ORIG_OUT_CP.load(Ordering::SeqCst);
        if cp != 0 {
            let _ = unsafe { SetConsoleOutputCP(cp) };
        }
    }
    if !in_h.is_null() && in_h != INVALID_HANDLE_VALUE {
        let _ = unsafe { SetConsoleMode(in_h, ORIG_IN_MODE.load(Ordering::SeqCst)) };
        let cp = ORIG_IN_CP.load(Ordering::SeqCst);
        if cp != 0 {
            let _ = unsafe { SetConsoleCP(cp) };
        }
    }
}

fn write_stderr_bytes(bytes: &[u8]) {
    let h = unsafe { GetStdHandle(STD_ERROR_HANDLE) };
    if h.is_null() || h == INVALID_HANDLE_VALUE {
        return;
    }
    write_handle_bytes(h, bytes);
}

fn write_handle_bytes(h: HANDLE, bytes: &[u8]) {
    let mut written: DWORD = 0;
    let _ = unsafe {
        WriteFile(
            h,
            bytes.as_ptr() as *const c_void,
            bytes.len() as DWORD,
            &mut written,
            ptr::null_mut(),
        )
    };
}

/// Install a panic hook that restores the console before the default hook
/// prints the panic message.  Idempotent.
pub fn install_panic_hook() {
    if PANIC_HOOK_INSTALLED.swap(true, Ordering::SeqCst) {
        return;
    }
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        emergency_restore();
        previous(info);
    }));
}

unsafe extern "system" fn ctrl_handler(ctrl_type: DWORD) -> BOOL {
    match ctrl_type {
        CTRL_C_EVENT | CTRL_BREAK_EVENT | CTRL_CLOSE_EVENT | CTRL_LOGOFF_EVENT
        | CTRL_SHUTDOWN_EVENT => {
            emergency_restore();
            // Return 0 so the default handler still terminates the process.
            0
        }
        _ => 0,
    }
}

// ---------------------------------------------------------------------------
// Console
// ---------------------------------------------------------------------------

pub struct Console {
    input: HANDLE,
    output: HANDLE,
    orig_input_mode: DWORD,
    orig_output_mode: DWORD,
    orig_input_cp: u32,
    orig_output_cp: u32,
    raw: bool,
    ctrl_handler_installed: bool,
    size: Size,
    orig_title: Vec<u16>,
    title_set: bool,
    pending_high_surrogate: Option<u16>,
}

impl Console {
    /// Open stdin/stdout and verify that both are real Win32 consoles.
    ///
    /// This is the non-TTY guard: redirected/piped stdio fails here with a
    /// clear `ConsoleError` instead of producing garbage or panicking.
    pub fn open() -> Result<Self, ConsoleError> {
        let input = unsafe { GetStdHandle(STD_INPUT_HANDLE) };
        let output = unsafe { GetStdHandle(STD_OUTPUT_HANDLE) };
        if input.is_null() || input == INVALID_HANDLE_VALUE {
            return Err(ConsoleError::custom(
                "GetStdHandle(STD_INPUT_HANDLE)",
                "stdin is not available",
            ));
        }
        if output.is_null() || output == INVALID_HANDLE_VALUE {
            return Err(ConsoleError::custom(
                "GetStdHandle(STD_OUTPUT_HANDLE)",
                "stdout is not available",
            ));
        }

        let mut input_mode: DWORD = 0;
        if unsafe { GetConsoleMode(input, &mut input_mode) } == 0 {
            return Err(ConsoleError::last("GetConsoleMode(stdin)"));
        }
        let mut output_mode: DWORD = 0;
        if unsafe { GetConsoleMode(output, &mut output_mode) } == 0 {
            return Err(ConsoleError::last("GetConsoleMode(stdout)"));
        }

        let mut console = Self {
            input,
            output,
            orig_input_mode: input_mode,
            orig_output_mode: output_mode,
            orig_input_cp: unsafe { GetConsoleCP() },
            orig_output_cp: unsafe { GetConsoleOutputCP() },
            raw: false,
            ctrl_handler_installed: false,
            size: Size::default(),
            orig_title: Vec::new(),
            title_set: false,
            pending_high_surrogate: None,
        };
        console.size = console.query_size()?;
        console.orig_title = console.query_title();
        Ok(console)
    }

    pub fn size(&self) -> Size {
        self.size
    }

    pub fn is_raw(&self) -> bool {
        // RAW_ACTIVE is cleared by emergency_restore() from the panic hook,
        // which cannot touch this struct.  Consult it so Drop does not write
        // a second leave-screen sequence after the output mode was restored.
        self.raw && RAW_ACTIVE.load(Ordering::SeqCst)
    }

    pub fn input_mode(&self) -> DWORD {
        self.orig_input_mode
    }

    pub fn output_mode(&self) -> DWORD {
        self.orig_output_mode
    }

    pub fn input_cp(&self) -> u32 {
        self.orig_input_cp
    }

    pub fn output_cp(&self) -> u32 {
        self.orig_output_cp
    }

    pub fn query_size(&mut self) -> Result<Size, ConsoleError> {
        let mut info: CONSOLE_SCREEN_BUFFER_INFO = Default::default();
        if unsafe { GetConsoleScreenBufferInfo(self.output, &mut info) } == 0 {
            return Err(ConsoleError::last("GetConsoleScreenBufferInfo"));
        }
        let cols = (info.srWindow.Right as i32 - info.srWindow.Left as i32 + 1)
            .clamp(1, u16::MAX as i32) as u16;
        let rows = (info.srWindow.Bottom as i32 - info.srWindow.Top as i32 + 1)
            .clamp(1, u16::MAX as i32) as u16;
        let size = Size {
            cols,
            rows,
            buffer_cols: (info.dwSize.X as i32).clamp(1, u16::MAX as i32) as u16,
            buffer_rows: (info.dwSize.Y as i32).clamp(1, u16::MAX as i32) as u16,
            left: info.srWindow.Left,
            top: info.srWindow.Top,
        };
        self.size = size;
        Ok(size)
    }

    pub fn cursor_position(&self) -> Result<(i16, i16), ConsoleError> {
        let mut info: CONSOLE_SCREEN_BUFFER_INFO = Default::default();
        if unsafe { GetConsoleScreenBufferInfo(self.output, &mut info) } == 0 {
            return Err(ConsoleError::last("GetConsoleScreenBufferInfo(cursor)"));
        }
        Ok((info.dwCursorPosition.X, info.dwCursorPosition.Y))
    }

    /// Refresh the cached size (called on WINDOW_BUFFER_SIZE_EVENT).
    pub fn refresh_size(&mut self) -> Result<Size, ConsoleError> {
        self.query_size()
    }

    /// Put the console into raw input mode and enable VT output.
    ///
    /// Input flags: line input, echo, processed input, quick-edit and VT input
    /// are cleared; window/mouse/extended flags are set.  Clearing
    /// ENABLE_PROCESSED_INPUT is what turns Ctrl+C into an ordinary
    /// KEY_EVENT instead of a process-wide CTRL_C_EVENT.
    pub fn enter_raw(&mut self) -> Result<(), ConsoleError> {
        if self.raw {
            return Ok(());
        }

        let mut in_mode = self.orig_input_mode;
        in_mode &= !(ENABLE_LINE_INPUT
            | ENABLE_ECHO_INPUT
            | ENABLE_PROCESSED_INPUT
            | ENABLE_QUICK_EDIT_MODE
            | ENABLE_VIRTUAL_TERMINAL_INPUT);
        in_mode |= ENABLE_WINDOW_INPUT | ENABLE_MOUSE_INPUT | ENABLE_EXTENDED_FLAGS;
        // INSERT_MODE is intentionally preserved (not set/cleared): it does
        // not affect us, and preserving it avoids surprising the shell.
        if unsafe { SetConsoleMode(self.input, in_mode) } == 0 {
            return Err(ConsoleError::last("SetConsoleMode(stdin, raw)"));
        }

        let out_mode = self.orig_output_mode
            | ENABLE_PROCESSED_OUTPUT
            | ENABLE_VIRTUAL_TERMINAL_PROCESSING;
        if unsafe { SetConsoleMode(self.output, out_mode) } == 0 {
            // Roll back the input mode before reporting the failure.
            let _ = unsafe { SetConsoleMode(self.input, self.orig_input_mode) };
            return Err(ConsoleError::last("SetConsoleMode(stdout, VT)"));
        }

        // UTF-8 is required for the half-block glyph (E2 96 80) when bytes are
        // written through WriteFile/stdout.  Failure is non-fatal: Windows
        // Terminal usually accepts UTF-8 regardless of the console CP.
        let _ = unsafe { SetConsoleOutputCP(65001) };
        let _ = unsafe { SetConsoleCP(65001) };

        self.raw = true;
        ORIG_IN_MODE.store(self.orig_input_mode, Ordering::SeqCst);
        ORIG_OUT_MODE.store(self.orig_output_mode, Ordering::SeqCst);
        ORIG_IN_CP.store(self.orig_input_cp, Ordering::SeqCst);
        ORIG_OUT_CP.store(self.orig_output_cp, Ordering::SeqCst);
        IN_HANDLE.store(self.input as isize, Ordering::SeqCst);
        OUT_HANDLE.store(self.output as isize, Ordering::SeqCst);
        RAW_ACTIVE.store(true, Ordering::SeqCst);
        Ok(())
    }

    /// Raw mode for a remote shell.
    ///
    /// Unlike [`Self::enter_raw`], VT input stays enabled: the console turns key
    /// presses into the byte sequences a terminal program expects. Forwarding
    /// those bytes to the remote PTY makes it see exactly what a local shell
    /// would, so no key mapping table is needed.
    pub fn enter_raw_vt_input(&mut self) -> Result<(), ConsoleError> {
        if self.raw {
            return Ok(());
        }
        let mut in_mode = self.orig_input_mode;
        in_mode &= !(ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT | ENABLE_PROCESSED_INPUT);
        in_mode |= ENABLE_VIRTUAL_TERMINAL_INPUT;
        if unsafe { SetConsoleMode(self.input, in_mode) } == 0 {
            return Err(ConsoleError::last("SetConsoleMode(stdin, raw vt)"));
        }
        let out_mode = self.orig_output_mode
            | ENABLE_PROCESSED_OUTPUT
            | ENABLE_VIRTUAL_TERMINAL_PROCESSING;
        if unsafe { SetConsoleMode(self.output, out_mode) } == 0 {
            let _ = unsafe { SetConsoleMode(self.input, self.orig_input_mode) };
            return Err(ConsoleError::last("SetConsoleMode(stdout, VT)"));
        }
        let _ = unsafe { SetConsoleOutputCP(65001) };
        let _ = unsafe { SetConsoleCP(65001) };

        self.raw = true;
        ORIG_IN_MODE.store(self.orig_input_mode, Ordering::SeqCst);
        ORIG_OUT_MODE.store(self.orig_output_mode, Ordering::SeqCst);
        ORIG_IN_CP.store(self.orig_input_cp, Ordering::SeqCst);
        ORIG_OUT_CP.store(self.orig_output_cp, Ordering::SeqCst);
        IN_HANDLE.store(self.input as isize, Ordering::SeqCst);
        OUT_HANDLE.store(self.output as isize, Ordering::SeqCst);
        RAW_ACTIVE.store(true, Ordering::SeqCst);
        Ok(())
    }

    /// Blocking read of console input bytes; only meaningful in VT input mode.
    pub fn read_stdin_bytes(&self, buf: &mut [u8]) -> Result<usize, ConsoleError> {
        read_stdin_blocking(buf)
    }

    /// Restore the original input/output modes, code pages and title.
    /// Best effort: errors are ignored because this also runs from Drop.
    pub fn leave_raw(&mut self) {
        if !self.raw {
            return;
        }
        let _ = unsafe { SetConsoleMode(self.output, self.orig_output_mode) };
        if self.orig_output_cp != 0 {
            let _ = unsafe { SetConsoleOutputCP(self.orig_output_cp) };
        }
        let _ = unsafe { SetConsoleMode(self.input, self.orig_input_mode) };
        if self.orig_input_cp != 0 {
            let _ = unsafe { SetConsoleCP(self.orig_input_cp) };
        }
        self.raw = false;
        RAW_ACTIVE.store(false, Ordering::SeqCst);
        self.restore_title();
    }

    pub fn install_ctrl_handler(&mut self) -> bool {
        if self.ctrl_handler_installed {
            return true;
        }
        let ok = unsafe { SetConsoleCtrlHandler(Some(ctrl_handler), 1) } != 0;
        self.ctrl_handler_installed = ok;
        ok
    }

    pub fn set_title(&mut self, title: &str) {
        if !self.title_set {
            self.orig_title = self.query_title();
        }
        let wide: Vec<u16> = title.encode_utf16().chain(std::iter::once(0)).collect();
        if unsafe { SetConsoleTitleW(wide.as_ptr()) } != 0 {
            self.title_set = true;
        }
    }

    fn query_title(&self) -> Vec<u16> {
        let mut buf = vec![0u16; 1024];
        let n = unsafe { GetConsoleTitleW(buf.as_mut_ptr(), buf.len() as DWORD) };
        if n > 0 {
            buf.truncate(n as usize);
            buf
        } else {
            Vec::new()
        }
    }

    fn restore_title(&mut self) {
        if self.title_set && !self.orig_title.is_empty() {
            let mut wide = self.orig_title.clone();
            wide.push(0);
            let _ = unsafe { SetConsoleTitleW(wide.as_ptr()) };
        }
        self.title_set = false;
    }

    pub fn flush_input(&self) -> Result<(), ConsoleError> {
        if unsafe { FlushConsoleInputBuffer(self.input) } == 0 {
            return Err(ConsoleError::last("FlushConsoleInputBuffer"));
        }
        Ok(())
    }

    pub fn pending_events(&self) -> Result<u32, ConsoleError> {
        let mut n: DWORD = 0;
        if unsafe { GetNumberOfConsoleInputEvents(self.input, &mut n) } == 0 {
            return Err(ConsoleError::last("GetNumberOfConsoleInputEvents"));
        }
        Ok(n)
    }

    /// Wait up to `timeout_ms` for one input record and decode it.
    ///
    /// `Ok(None)` means timeout.  `ReadConsoleInputW` is blocking, so the wait
    /// is done on the waitable console input handle first.
    pub fn read_event(&mut self, timeout_ms: u32) -> Result<Option<InputEvent>, ConsoleError> {
        let wait = unsafe { WaitForSingleObject(self.input, timeout_ms) };
        if wait == WAIT_TIMEOUT {
            return Ok(None);
        }
        if wait == WAIT_FAILED {
            return Err(ConsoleError::last("WaitForSingleObject(stdin)"));
        }
        if wait != WAIT_OBJECT_0 {
            return Err(ConsoleError::custom(
                "WaitForSingleObject(stdin)",
                format!("unexpected wait result 0x{wait:08X}"),
            ));
        }

        let mut rec = INPUT_RECORD::default();
        let mut read: DWORD = 0;
        if unsafe { ReadConsoleInputW(self.input, &mut rec, 1, &mut read) } == 0 {
            return Err(ConsoleError::last("ReadConsoleInputW"));
        }
        if read == 0 {
            return Ok(None);
        }
        self.decode_record(&rec).map(Some)
    }

    pub fn read_all_pending(&mut self, max: usize) -> Result<Vec<InputEvent>, ConsoleError> {
        let mut events = Vec::new();
        for _ in 0..max {
            match self.read_event(0)? {
                Some(ev) => events.push(ev),
                None => break,
            }
        }
        Ok(events)
    }

    fn decode_record(&mut self, rec: &INPUT_RECORD) -> Result<InputEvent, ConsoleError> {
        match rec.EventType {
            KEY_EVENT => {
                let key = unsafe { rec.Event.KeyEvent };
                Ok(InputEvent::Key(self.decode_key(key)))
            }
            MOUSE_EVENT => {
                let mouse = unsafe { rec.Event.MouseEvent };
                Ok(InputEvent::Mouse(self.decode_mouse(mouse)))
            }
            WINDOW_BUFFER_SIZE_EVENT => {
                let size = self.refresh_size()?;
                Ok(InputEvent::Resize(size))
            }
            FOCUS_EVENT => {
                let focus = unsafe { rec.Event.FocusEvent };
                Ok(InputEvent::Focus(focus.bSetFocus != 0))
            }
            MENU_EVENT => {
                let menu = unsafe { rec.Event.MenuEvent };
                Ok(InputEvent::Menu(menu.dwCommandId))
            }
            other => Ok(InputEvent::Unknown(other)),
        }
    }

    fn decode_key(&mut self, key: KEY_EVENT_RECORD) -> KeyEvent {
        let unicode = unsafe { key.uChar.UnicodeChar };
        let ch = self.decode_unicode(unicode);
        let state = key.dwControlKeyState;
        KeyEvent {
            vk: key.wVirtualKeyCode,
            scan_code: key.wVirtualScanCode,
            unicode,
            ch,
            down: key.bKeyDown != 0,
            repeat: key.wRepeatCount.max(1),
            modifiers: KeyModifiers {
                ctrl: state & (LEFT_CTRL_PRESSED | RIGHT_CTRL_PRESSED) != 0,
                alt: state & (LEFT_ALT_PRESSED | RIGHT_ALT_PRESSED) != 0,
                shift: state & SHIFT_PRESSED != 0,
            },
            enhanced: state & ENHANCED_KEY != 0,
            caps_lock: state & CAPSLOCK_ON != 0,
            num_lock: state & NUMLOCK_ON != 0,
            scroll_lock: state & SCROLLLOCK_ON != 0,
        }
    }

    fn decode_unicode(&mut self, unit: u16) -> Option<char> {
        if unit == 0 {
            self.pending_high_surrogate = None;
            return None;
        }
        if (0xD800..=0xDBFF).contains(&unit) {
            self.pending_high_surrogate = Some(unit);
            return None;
        }
        if (0xDC00..=0xDFFF).contains(&unit) {
            if let Some(high) = self.pending_high_surrogate.take() {
                let cp = 0x1_0000
                    + (((high as u32 - 0xD800) << 10) | (unit as u32 - 0xDC00));
                return char::from_u32(cp);
            }
            return None;
        }
        self.pending_high_surrogate = None;
        char::from_u32(unit as u32)
    }

    fn decode_mouse(&self, mouse: MOUSE_EVENT_RECORD) -> MouseEvent {
        let state = mouse.dwButtonState;
        let flags = mouse.dwEventFlags;
        let wheel = if flags & MOUSE_WHEELED != 0 {
            (state >> 16) as u16 as i16
        } else {
            0
        };
        let horizontal_wheel = if flags & MOUSE_HWHEELED != 0 {
            (state >> 16) as u16 as i16
        } else {
            0
        };
        let x = mouse.dwMousePosition.X;
        let y = mouse.dwMousePosition.Y;
        let viewport_x = (x as i32 - self.size.left as i32)
            .clamp(i16::MIN as i32, i16::MAX as i32) as i16;
        let viewport_y = (y as i32 - self.size.top as i32)
            .clamp(i16::MIN as i32, i16::MAX as i32) as i16;
        MouseEvent {
            x,
            y,
            viewport_x,
            viewport_y,
            buttons: MouseButtons {
                left: state & FROM_LEFT_1ST_BUTTON_PRESSED != 0,
                right: state & RIGHTMOST_BUTTON_PRESSED != 0,
                middle: state & FROM_LEFT_2ND_BUTTON_PRESSED != 0,
                x1: state & FROM_LEFT_3RD_BUTTON_PRESSED != 0,
                x2: state & FROM_LEFT_4TH_BUTTON_PRESSED != 0,
            },
            wheel,
            horizontal_wheel,
            moved: flags & MOUSE_MOVED != 0,
            double_click: flags & DOUBLE_CLICK != 0,
            modifiers: KeyModifiers {
                ctrl: mouse.dwControlKeyState & (LEFT_CTRL_PRESSED | RIGHT_CTRL_PRESSED) != 0,
                alt: mouse.dwControlKeyState & (LEFT_ALT_PRESSED | RIGHT_ALT_PRESSED) != 0,
                shift: mouse.dwControlKeyState & SHIFT_PRESSED != 0,
            },
            raw_button_state: state,
            raw_event_flags: flags,
        }
    }

    /// Inject records into our own console input buffer.  Used by the
    /// `--inject-test` mode and unit-ish integration checks; a real
    /// application never needs this.
    pub fn inject_records(&self, records: &[INPUT_RECORD]) -> Result<(), ConsoleError> {
        let mut written: DWORD = 0;
        if unsafe {
            WriteConsoleInputW(
                self.input,
                records.as_ptr(),
                records.len() as DWORD,
                &mut written,
            )
        } == 0
        {
            return Err(ConsoleError::last("WriteConsoleInputW"));
        }
        if written as usize != records.len() {
            return Err(ConsoleError::custom(
                "WriteConsoleInputW",
                format!("wrote {written}/{} records", records.len()),
            ));
        }
        Ok(())
    }
}

impl Drop for Console {
    fn drop(&mut self) {
        self.leave_raw();
    }
}

// ---------------------------------------------------------------------------
// Output helpers
// ---------------------------------------------------------------------------

/// Write raw bytes to stdout and flush.  The caller has already put the
/// console into VT-processing mode and (on the UTF-8 path) code page 65001.
pub fn write_stdout(bytes: &[u8]) -> io::Result<()> {
    use std::io::Write;
    let mut out = io::stdout().lock();
    out.write_all(bytes)?;
    out.flush()
}

/// True when COLORTERM advertises 24-bit color.  Windows Terminal and
/// conhost often leave this unset, so callers must be prepared to pass
/// `--truecolor` explicitly for benchmarking.
pub fn env_supports_truecolor() -> bool {
    match std::env::var("COLORTERM") {
        Ok(v) => {
            let v = v.to_ascii_lowercase();
            v.contains("truecolor") || v.contains("24bit")
        }
        Err(_) => false,
    }
}

/// Blocking read of this process' stdin bytes, callable from any thread.
/// Only meaningful in VT input mode, where the console emits the byte
/// sequences a terminal program expects.
pub fn read_stdin_blocking(buf: &mut [u8]) -> Result<usize, ConsoleError> {
    let input = unsafe { GetStdHandle(STD_INPUT_HANDLE) };
    if input.is_null() || input == INVALID_HANDLE_VALUE {
        return Err(ConsoleError::custom(
            "GetStdHandle(STD_INPUT_HANDLE)",
            "stdin is not available",
        ));
    }
    let mut read: DWORD = 0;
    let ok = unsafe {
        ReadFile(
            input,
            buf.as_mut_ptr() as *mut c_void,
            buf.len() as DWORD,
            &mut read,
            ptr::null_mut(),
        )
    };
    if ok == 0 {
        return Err(ConsoleError::last("ReadFile(stdin)"));
    }
    Ok(read as usize)
}

/// Visible window size of this process' console, callable from any thread.
pub fn query_console_size() -> Option<Size> {
    let output = unsafe { GetStdHandle(STD_OUTPUT_HANDLE) };
    if output.is_null() || output == INVALID_HANDLE_VALUE {
        return None;
    }
    let mut info: CONSOLE_SCREEN_BUFFER_INFO = Default::default();
    if unsafe { GetConsoleScreenBufferInfo(output, &mut info) } == 0 {
        return None;
    }
    Some(Size {
        cols: (info.srWindow.Right as i32 - info.srWindow.Left as i32 + 1).clamp(1, u16::MAX as i32)
            as u16,
        rows: (info.srWindow.Bottom as i32 - info.srWindow.Top as i32 + 1).clamp(1, u16::MAX as i32)
            as u16,
        buffer_cols: (info.dwSize.X as i32).clamp(1, u16::MAX as i32) as u16,
        buffer_rows: (info.dwSize.Y as i32).clamp(1, u16::MAX as i32) as u16,
        left: info.srWindow.Left,
        top: info.srWindow.Top,
    })
}

// ---------------------------------------------------------------------------
// Synthetic records for --inject-test
// ---------------------------------------------------------------------------

pub fn make_key_record(
    down: bool,
    vk: u16,
    scan: u16,
    unicode: u16,
    ctrl: bool,
    alt: bool,
    shift: bool,
) -> INPUT_RECORD {
    let mut state: DWORD = 0;
    if ctrl {
        state |= LEFT_CTRL_PRESSED;
    }
    if alt {
        state |= LEFT_ALT_PRESSED;
    }
    if shift {
        state |= SHIFT_PRESSED;
    }
    INPUT_RECORD {
        EventType: KEY_EVENT,
        Event: INPUT_RECORD_UNION {
            KeyEvent: KEY_EVENT_RECORD {
                bKeyDown: if down { 1 } else { 0 },
                wRepeatCount: 1,
                wVirtualKeyCode: vk,
                wVirtualScanCode: scan,
                uChar: KEY_EVENT_CHAR { UnicodeChar: unicode },
                dwControlKeyState: state,
            },
        },
    }
}

pub fn make_mouse_record(
    x: i16,
    y: i16,
    button_state: u32,
    event_flags: u32,
    ctrl: bool,
    alt: bool,
    shift: bool,
) -> INPUT_RECORD {
    let mut state: DWORD = 0;
    if ctrl {
        state |= LEFT_CTRL_PRESSED;
    }
    if alt {
        state |= LEFT_ALT_PRESSED;
    }
    if shift {
        state |= SHIFT_PRESSED;
    }
    INPUT_RECORD {
        EventType: MOUSE_EVENT,
        Event: INPUT_RECORD_UNION {
            MouseEvent: MOUSE_EVENT_RECORD {
                dwMousePosition: COORD { X: x, Y: y },
                dwButtonState: button_state,
                dwControlKeyState: state,
                dwEventFlags: event_flags,
            },
        },
    }
}

pub fn make_resize_record(cols: i16, rows: i16) -> INPUT_RECORD {
    INPUT_RECORD {
        EventType: WINDOW_BUFFER_SIZE_EVENT,
        Event: INPUT_RECORD_UNION {
            WindowBufferSizeEvent: WINDOW_BUFFER_SIZE_RECORD {
                dwSize: COORD { X: cols, Y: rows },
            },
        },
    }
}

pub fn make_focus_record(focused: bool) -> INPUT_RECORD {
    INPUT_RECORD {
        EventType: FOCUS_EVENT,
        Event: INPUT_RECORD_UNION {
            FocusEvent: FOCUS_EVENT_RECORD {
                bSetFocus: if focused { 1 } else { 0 },
            },
        },
    }
}

// ---------------------------------------------------------------------------
// Tests for pure decoding helpers
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    fn key(vk: u16, unicode: u16, ctrl: bool) -> KeyEvent {
        KeyEvent {
            vk,
            scan_code: 0,
            unicode,
            ch: char::from_u32(unicode as u32),
            down: true,
            repeat: 1,
            modifiers: KeyModifiers {
                ctrl,
                alt: false,
                shift: false,
            },
            enhanced: false,
            caps_lock: false,
            num_lock: false,
            scroll_lock: false,
        }
    }

    #[test]
    fn ctrl_letters_and_ctrl_bracket() {
        assert_eq!(key(VK_A, 0x01, true).code(), KeyCode::CtrlLetter(b'a'));
        assert_eq!(key(VK_C, 0x03, true).code(), KeyCode::CtrlLetter(b'c'));
        assert!(key(VK_C, 0x03, true).is_ctrl_c());
        assert_eq!(key(VK_OEM_6, 0x1D, true).code(), KeyCode::CtrlChar(']'));
        assert_eq!(key(VK_OEM_4, 0x1B, true).code(), KeyCode::CtrlChar('['));
        assert_eq!(key(VK_ESCAPE, 0x1B, false).code(), KeyCode::Escape);
    }

    #[test]
    fn function_and_navigation_keys() {
        assert_eq!(key(VK_F1, 0, false).code(), KeyCode::Function(1));
        assert_eq!(key(VK_F12, 0, false).code(), KeyCode::Function(12));
        assert_eq!(key(VK_LEFT, 0, false).code(), KeyCode::Arrow(Direction::Left));
        assert_eq!(key(VK_HOME, 0, false).code(), KeyCode::Home);
        assert_eq!(key(VK_NEXT, 0, false).code(), KeyCode::PageDown);
        assert_eq!(key(VK_INSERT, 0, false).code(), KeyCode::Insert);
        assert_eq!(key(VK_DELETE, 0, false).code(), KeyCode::Delete);
    }
}
