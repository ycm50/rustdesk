//! Session bootstrap for the terminal front end.
//!
//! The remote shell runs over the existing terminal protocol: stdin bytes go
//! out as `TerminalAction::data`, and the peer's output comes back through
//! `TerminalHandler::handle_terminal_response`, which writes it straight to
//! stdout. Nothing here draws a UI.
//!
//! With a console attached the local side goes into raw VT-input mode, so the
//! console emits the byte sequences a terminal program expects and the remote
//! PTY sees exactly what a local shell would. With piped stdio the same loop
//! runs without raw mode, which also makes `rustdesk --terminal <id> < script`
//! usable.

use std::{
    io::Read,
    sync::mpsc,
    thread,
    time::{Duration, Instant},
};

use base::message_proto::FileType;
use hbb_common::{log, rendezvous_proto::ConnType};

use crate::{
    client::FileManager,
    terminal::{console, handler::TerminalHandler},
    ui_session_interface::Session,
};

/// Ctrl+] — the conventional "escape to the local side" key.
const EXIT_SEQUENCE: u8 = 0x1d;
const RESIZE_INTERVAL: Duration = Duration::from_millis(500);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const EXIT_GRACE: Duration = Duration::from_secs(10);
const TRANSFER_TIMEOUT: Duration = Duration::from_secs(600);
const PIPE_SIZE: (u16, u16) = (80, 24);

pub fn run_shell(id: String, password: String) -> i32 {
    let session = build_session(id.clone(), password);
    match console::Console::open() {
        Ok(mut term) => {
            console::install_panic_hook();
            term.install_ctrl_handler();
            if let Err(err) = term.enter_raw_vt_input() {
                eprintln!("{err}");
                return 2;
            }
            term.set_title(&format!("RustDesk - {id}"));
            let code = drive(&session, &stdin_events(), &console_size);
            term.leave_raw();
            code
        }
        Err(err) => {
            log::info!("no console attached ({}), using pipe mode", err);
            drive(&session, &stdin_events(), &|| PIPE_SIZE)
        }
    }
}

fn build_session(id: String, password: String) -> Session<TerminalHandler> {
    let session: Session<TerminalHandler> = Session {
        password,
        ..Default::default()
    };
    session
        .lc
        .write()
        .unwrap()
        .initialize(id, ConnType::TERMINAL, None, false, None, None, None);
    session.reconnect(false);
    session
}

fn console_size() -> (u16, u16) {
    console::query_console_size()
        .map(|size| (size.cols, size.rows))
        .unwrap_or(PIPE_SIZE)
}

/// stdin is read on its own thread so the session loop can also poll state.
fn stdin_events() -> mpsc::Receiver<Vec<u8>> {
    let (tx, rx) = mpsc::channel::<Vec<u8>>();
    thread::spawn(move || {
        let mut buf = [0u8; 4096];
        let mut stdin = std::io::stdin();
        loop {
            match stdin.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if tx.send(buf[..n].to_vec()).is_err() {
                        break;
                    }
                }
                Err(err) => {
                    log::error!("read stdin failed: {}", err);
                    break;
                }
            }
        }
    });
    rx
}

fn drive(
    session: &Session<TerminalHandler>,
    rx: &mpsc::Receiver<Vec<u8>>,
    size: &dyn Fn() -> (u16, u16),
) -> i32 {
    let mut opened = false;
    let mut pending: Vec<u8> = Vec::new();
    let (mut cols, mut rows) = size();
    let mut last_resize_check = Instant::now();
    let connect_start = Instant::now();
    // stdin closed (end of a script): flush what is left, ask the shell to exit
    // and wait briefly for the rest of its output.
    let mut eof = false;
    let mut exit_sent = false;
    let mut exit_deadline: Option<Instant> = None;
    loop {
        if session.is_closed() {
            let _ = console::write_stdout(b"\r\n[terminal closed]\r\n");
            return 0;
        }
        if !opened {
            if session.is_connected() {
                session.open_terminal(0, rows as u32, cols as u32);
                opened = true;
            } else if connect_start.elapsed() > CONNECT_TIMEOUT {
                eprintln!("connect timed out");
                return 1;
            }
        }
        if opened {
            if let Some(text) = take_utf8(&mut pending) {
                session.send_terminal_input(0, text);
            }
            if eof && !exit_sent {
                session.send_terminal_input(0, "exit\r".to_owned());
                exit_sent = true;
                exit_deadline = Some(Instant::now() + EXIT_GRACE);
            }
        }
        if let Some(deadline) = exit_deadline {
            if Instant::now() >= deadline {
                return 0;
            }
        }
        match rx.recv_timeout(Duration::from_millis(100)) {
            Ok(bytes) => {
                if bytes.contains(&EXIT_SEQUENCE) {
                    return 0;
                }
                pending.extend_from_slice(&bytes);
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => eof = true,
        }
        if last_resize_check.elapsed() >= RESIZE_INTERVAL {
            last_resize_check = Instant::now();
            let (new_cols, new_rows) = size();
            if (new_cols, new_rows) != (cols, rows) {
                cols = new_cols;
                rows = new_rows;
                if opened {
                    session.resize_terminal(0, rows as u32, cols as u32);
                }
            }
        }
    }
}

/// Local listener that tunnels to a service on the peer.
///
/// The session's own `io_loop` owns the forwarding once the args are set; this
/// only keeps the process attached and reports connection changes.
pub fn run_port_forward(id: String, password: String, forward: Vec<String>) -> i32 {
    if forward.len() != 3 {
        eprintln!("usage: rustdesk --port-forward <id> [pass] <local_port> <remote_host> <remote_port>");
        return 2;
    }
    let Ok(local_port) = forward[0].parse::<u16>() else {
        eprintln!("local port must be a number: {}", forward[0]);
        return 2;
    };
    let session: Session<TerminalHandler> = Session {
        password,
        args: forward.clone(),
        ..Default::default()
    };
    session.lc.write().unwrap().initialize(
        id.clone(),
        ConnType::PORT_FORWARD,
        None,
        false,
        None,
        None,
        None,
    );
    session.reconnect(false);
    println!(
        "Forwarding 127.0.0.1:{local_port} -> {}:{} via {id}. Press Ctrl+C to stop.",
        forward[1], forward[2]
    );
    let mut last_connected = false;
    let mut last_report = Instant::now();
    loop {
        thread::sleep(Duration::from_millis(500));
        let connected = session.is_connected();
        if connected != last_connected || last_report.elapsed() > Duration::from_secs(60) {
            println!(
                "{}",
                if connected {
                    "connected"
                } else {
                    "connecting"
                }
            );
            last_connected = connected;
            last_report = Instant::now();
        }
    }
}

/// Non-interactive file transfer over the existing file protocol.
///
/// `ls <remote_dir>`, `get <remote_file> <local_dir>`, `put <local_file>
/// <remote_dir>`. The session's own io_loop runs the job; this waits for it and
/// reports the outcome.
pub fn run_file_transfer(id: String, password: String, cmd: String, args: Vec<String>) -> i32 {
    let session: Session<TerminalHandler> = Session {
        password,
        ..Default::default()
    };
    session.lc.write().unwrap().initialize(
        id,
        ConnType::FILE_TRANSFER,
        None,
        false,
        None,
        None,
        None,
    );
    session.reconnect(false);
    if !wait_connected(&session, CONNECT_TIMEOUT) {
        eprintln!("connect timed out");
        return 1;
    }
    match cmd.as_str() {
        "ls" => list_remote_dir(&session, args.first().cloned().unwrap_or_default()),
        "get" => transfer(&session, &args, true),
        "put" => transfer(&session, &args, false),
        "" => {
            eprintln!("usage: rustdesk --file-transfer <id> [pass] <ls|get|put> ...");
            2
        }
        other => {
            eprintln!("unknown file-transfer command: {other}");
            2
        }
    }
}

fn wait_connected(session: &Session<TerminalHandler>, timeout: Duration) -> bool {
    let start = Instant::now();
    while start.elapsed() < timeout {
        if session.is_connected() {
            return true;
        }
        if session.is_closed() {
            return false;
        }
        thread::sleep(Duration::from_millis(100));
    }
    false
}

fn list_remote_dir(session: &Session<TerminalHandler>, path: String) -> i32 {
    session.read_remote_dir(path, false);
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(10) {
        if let Some((dir, entries)) = session.take_folder() {
            println!("{dir}");
            for entry in entries.iter() {
                let kind = if entry.entry_type.value() == FileType::Dir as i32 {
                    "d"
                } else {
                    "-"
                };
                println!("{kind} {:>12}  {}", entry.size, entry.name);
            }
            return 0;
        }
        thread::sleep(Duration::from_millis(100));
    }
    eprintln!("no response from peer");
    1
}

fn transfer(session: &Session<TerminalHandler>, args: &[String], download: bool) -> i32 {
    if args.len() != 2 {
        eprintln!("usage: rustdesk --file-transfer <id> [pass] <get|put> <src> <dst>");
        return 2;
    }
    const JOB_ID: i32 = 1;
    // `file_num` is the index of the first file in the job, not a count: 1 would
    // start past the only entry and the peer would report an empty job.
    session.send_files(
        JOB_ID,
        FileType::File as i32,
        args[0].clone(),
        args[1].clone(),
        0,
        false,
        download,
    );
    let (confirm_tx, confirm_rx) = mpsc::channel();
    session.set_confirm_sender(confirm_tx);
    let session_for_confirm = session.clone();
    thread::spawn(move || {
        while let Ok(request) = confirm_rx.recv() {
            session_for_confirm.set_confirm_override_file(
                request.id,
                request.file_num,
                true,
                true,
                request.is_upload,
            );
        }
    });
    let start = Instant::now();
    let mut reported = 0;
    loop {
        if let Some(job) = session.job() {
            if let Some(err) = job.error {
                eprintln!("transfer failed: {err}");
                return 1;
            }
            if job.done {
                if job.finished_size > 0.0 {
                    println!("done: {} file(s), {} bytes", job.file_num, job.finished_size as u64);
                } else {
                    println!("done: {} file(s)", job.file_num);
                }
                return 0;
            }
            let megabytes = job.finished_size / (1024.0 * 1024.0);
            if megabytes as i64 > reported {
                reported = megabytes as i64;
                println!("{megabytes:.1} MiB at {:.1} MiB/s", job.speed / (1024.0 * 1024.0));
            }
        }
        if start.elapsed() > TRANSFER_TIMEOUT {
            eprintln!("transfer timed out");
            return 1;
        }
        thread::sleep(Duration::from_millis(300));
    }
}

/// Split off the longest valid UTF-8 prefix, keeping an incomplete trailing
/// character for the next read. Input is text, but a paste can be split across
/// two reads.
fn take_utf8(pending: &mut Vec<u8>) -> Option<String> {
    match std::str::from_utf8(pending) {
        Ok(text) => {
            let text = text.to_owned();
            pending.clear();
            Some(text)
        }
        Err(err) => {
            let valid = err.valid_up_to();
            if valid == 0 {
                return None;
            }
            let text = String::from_utf8(pending[..valid].to_vec()).ok();
            pending.drain(..valid);
            text
        }
    }
}
