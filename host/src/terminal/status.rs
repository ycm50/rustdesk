//! Headless host: banner, live log echo, and keeping the process attached.

use std::{
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    path::PathBuf,
    thread,
    time::{Duration, SystemTime},
};

use hbb_common::{config::Config, log, password_security};

/// Log lines echoed without `--verbose`. The host writes connections, logins and
/// failures to the log file; the console mirrors those so an operator sees them
/// without opening the file.
const EVENT_KEYWORDS: [&str; 8] = [
    "connection",
    "connected",
    "disconnect",
    "login",
    "relay",
    "error",
    "failed",
    "password",
];

pub fn run_host(server_started: bool, verbose: bool) -> i32 {
    if !server_started {
        thread::spawn(|| crate::start_server(false, false));
    }
    // The server publishes its temporary password and opens its log file a moment
    // after it starts; poll instead of assuming a fixed delay.
    let mut log_file = newest_log_file();
    for _ in 0..20 {
        if log_file.is_some() {
            break;
        }
        thread::sleep(Duration::from_millis(200));
        log_file = newest_log_file();
    }
    print_banner(log_file.is_some());
    match log_file {
        Some(path) => follow_log(path, verbose),
        None => idle(),
    }
}

fn newest_log_file() -> Option<PathBuf> {
    let dir = Config::log_path();
    let entries = fs::read_dir(&dir).ok()?;
    let mut newest: Option<(SystemTime, PathBuf)> = None;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().map(|ext| ext == "log") != Some(true) {
            continue;
        }
        let Ok(modified) = entry.metadata().and_then(|meta| meta.modified()) else {
            continue;
        };
        if newest
            .as_ref()
            .map(|(newest_modified, _)| modified > *newest_modified)
            .unwrap_or(true)
        {
            newest = Some((modified, path));
        }
    }
    newest.map(|(_, path)| path)
}

/// Write one chunk to stdout, tolerating a closed pipe: the host outlives
/// whoever started it, so losing the console must not end the process.
fn out(text: &str) {
    let mut stdout = std::io::stdout().lock();
    let _ = stdout.write_all(text.as_bytes());
    let _ = stdout.flush();
}

fn print_banner(live_log: bool) {
    // The permanent password is the one the operator chose; the temporary one is
    // regenerated on every start and is only an alternative, so while a fixed one is in
    // force only that one is printed.
    let has_fixed = Config::has_permanent_password();
    let mut text = format!(
        "\nRustDesk {} 终端被控端\n  设备 ID    : {}\n",
        crate::VERSION,
        Config::get_id()
    );
    if has_fixed {
        text.push_str(&format!(
            "  固定密码   : {}（用 `rustdesk --password <密码>` 修改）\n",
            crate::password_file::banner_text()
        ));
    } else {
        text.push_str("  固定密码   : 未设置（建议执行 `rustdesk --password <密码>`）\n");
        text.push_str(&format!(
            "  临时密码   : {}（本次启动有效）\n",
            current_temporary_password()
        ));
    }
    text.push_str(&format!(
        "  中继服务器 : {}\n  日志目录   : {}\n",
        Config::get_rendezvous_server(),
        Config::log_path().display()
    ));
    text.push_str(&permission_line());
    text.push('\n');
    if live_log {
        text.push_str("下面实时回显被控端事件，按 Ctrl+C 停止。\n");
    } else {
        text.push_str("等待远端连接；详细日志见上面的日志目录，按 Ctrl+C 停止。\n");
    }
    text.push('\n');
    out(&text);
}

/// Mouse and keyboard injection goes through `SendInput`, which a non-elevated
/// process cannot always reach; say so instead of letting the peer wonder why
/// the picture moves but the pointer does not.
fn permission_line() -> String {
    #[cfg(windows)]
    match crate::platform::is_elevated(None) {
        Ok(true) => "  权限       : 管理员（可正常注入远端鼠标键盘）\n".to_owned(),
        Ok(false) => {
            "  权限       : 普通用户（远端鼠标键盘会失效，请以管理员身份运行）\n".to_owned()
        }
        Err(_) => String::new(),
    }
    #[cfg(not(windows))]
    String::new()
}

/// The password a peer has to type. A standalone host owns the server, so an
/// empty value means nobody published one yet: publish it here rather than
/// printing nothing.
fn current_temporary_password() -> String {
    let mut password = password_security::temporary_password();
    if password.is_empty() {
        password_security::update_temporary_password();
        password = password_security::temporary_password();
    }
    if password.is_empty() {
        "(not published yet)".to_owned()
    } else {
        password
    }
}

fn is_event(line: &str) -> bool {
    let line = line.to_lowercase();
    EVENT_KEYWORDS.iter().any(|keyword| line.contains(keyword))
}

fn idle() -> ! {
    loop {
        thread::sleep(Duration::from_secs(3600));
    }
}

fn follow_log(path: PathBuf, verbose: bool) -> ! {
    let Ok(mut file) = File::open(&path) else {
        log::warn!("Cannot open {}; host keeps running without live output.", path.display());
        return idle();
    };
    // Only echo what happens from now on, not the whole history.
    if let Err(err) = file.seek(SeekFrom::End(0)) {
        log::warn!("Cannot seek {}: {}", path.display(), err);
    }
    let mut raw = Vec::new();
    loop {
        raw.clear();
        match file.read_to_end(&mut raw) {
            Ok(0) => thread::sleep(Duration::from_millis(250)),
            Ok(_) => {
                for line in String::from_utf8_lossy(&raw).lines() {
                    if verbose || is_event(line) {
                        out(&format!("{line}\n"));
                    }
                }
            }
            Err(err) => {
                log::error!("Cannot read {}: {}", path.display(), err);
                thread::sleep(Duration::from_secs(1));
            }
        }
    }
}
