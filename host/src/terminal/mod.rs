//! Console front end, used instead of the desktop GUI builds.
//!
//! The process either hosts this machine (headless) or opens a remote shell in
//! the current console.

pub mod status;
#[cfg(windows)]
pub mod app;
#[cfg(windows)]
pub mod cm;
#[cfg(windows)]
pub mod console;
#[cfg(windows)]
pub mod handler;
#[cfg(windows)]
pub mod render;
#[cfg(windows)]
pub mod screen;

const USAGE: &str = "\
RustDesk (terminal build)

Usage:
  rustdesk                          Run as a headless host in this console
  rustdesk --headless [-v]          Start the host server explicitly, then stay attached
  rustdesk --connect <id> [pass]    Show the peer's desktop as ANSI half-blocks
  rustdesk --terminal <id> [pass]   Open a remote shell in this console (Ctrl+] quits)
  rustdesk --file-transfer <id> [pass] <ls|get|put> ...
                                    List, download or upload files
  rustdesk --port-forward <id> [pass] <local_port> <host> <port>
                                    Tunnel a local port to the peer
  rustdesk --get-id                 Print this device ID
  rustdesk --password <pass>        Set the permanent password
  rustdesk --option <k> [<v>]       Read or write a configuration option
  rustdesk --version                Print the version

Options:
  -v, --verbose                     Echo every host log line to stdout
  -h, --help                        Print this help

Environment:
  RUSTDESK_TERM_256=1               Use 256 colours instead of truecolor
  RUSTDESK_CONFIG_DIR=<path>        Keep configuration outside the data/ folder
";

pub fn run(args: &mut Vec<String>) -> i32 {
    // Only an argument-less launch reaches here with a server thread already running, because
    // core_main starts it before dispatching. Read that before --relay-bridge is taken out,
    // otherwise "rustdesk --relay-bridge <url>" would look argument-less afterwards and no
    // server would ever be started.
    let server_started = args.is_empty();
    // A tray launch hides the console before anything can print to it; the decision is taken
    // here, while --relay-bridge is still in the argument list.
    #[cfg(windows)]
    let tray_mode = is_tray_launch(args);
    #[cfg(windows)]
    if tray_mode {
        crate::tray::console::detach();
    }
    // --relay-bridge is a modifier, not a subcommand: it has to be pulled out before the
    // first-argument dispatch so that --headless --relay-bridge <url> works.
    let relay_requested = take_relay_bridge(args);
    if relay_requested == RelayRequest::Invalid {
        return 2;
    }

    if let Some(first) = args.first() {
        match first.as_str() {
            "-h" | "--help" => {
                print!("{USAGE}");
                return 0;
            }
            "--terminal" => {
                #[cfg(windows)]
                {
                    let Some(id) = args.get(1).cloned() else {
                        eprintln!("--terminal needs a peer ID");
                        return 2;
                    };
                    let password = args.get(2).cloned().unwrap_or_default();
                    return app::run_shell(id, password);
                }
                #[cfg(not(windows))]
                {
                    eprintln!("--terminal is only implemented on Windows in this build");
                    return 2;
                }
            }
            "--connect" => {
                #[cfg(windows)]
                {
                    let Some(id) = args.get(1).cloned() else {
                        eprintln!("--connect needs a peer ID");
                        return 2;
                    };
                    let password = args.get(2).cloned().unwrap_or_default();
                    return screen::run(id, password);
                }
                #[cfg(not(windows))]
                {
                    eprintln!("--connect is only implemented on Windows in this build");
                    return 2;
                }
            }
            "--port-forward" => {
                // app is declared under cfg(windows): on any other target these two commands have
                // no implementation to call, so they must answer like --terminal and --connect do
                // instead of referencing the module.
                #[cfg(windows)]
                {
                    let Some(id) = args.get(1).cloned() else {
                        eprintln!("--port-forward needs a peer ID");
                        return 2;
                    };
                    let password = args.get(2).cloned().unwrap_or_default();
                    let forward = args.get(3..).unwrap_or_default().to_vec();
                    return app::run_port_forward(id, password, forward);
                }
                #[cfg(not(windows))]
                {
                    eprintln!("--port-forward is only implemented on Windows in this build");
                    return 2;
                }
            }
            "--file-transfer" => {
                #[cfg(windows)]
                {
                    let Some(id) = args.get(1).cloned() else {
                        eprintln!("--file-transfer needs a peer ID");
                        return 2;
                    };
                    let password = args.get(2).cloned().unwrap_or_default();
                    let cmd = args.get(3).cloned().unwrap_or_default();
                    let rest = args.get(4..).unwrap_or_default().to_vec();
                    return app::run_file_transfer(id, password, cmd, rest);
                }
                #[cfg(not(windows))]
                {
                    eprintln!("--file-transfer is only implemented on Windows in this build");
                    return 2;
                }
            }
            // The host spawns `--cm` for sessions that need an operator window
            // (file transfer, port forward, RDP); with no window it answers
            // automatically instead of dying and closing the session.
            "--cm" => {
                #[cfg(windows)]
                {
                    return cm::run();
                }
                #[cfg(not(windows))]
                {
                    return 0;
                }
            }
            // Reached from `core_main()` on autostart runs; the dispatch below sends it to
            // the tray, exactly like an argument-less launch.
            "--tray" | "--headless" | "-v" | "--verbose" => {}
            other => {
                eprintln!("Unknown argument: {other}");
                eprint!("{USAGE}");
                return 2;
            }
        }
    }
    let verbose = args.iter().any(|arg| arg == "-v" || arg == "--verbose");

    // An argument-less run is the desktop case: keep the host in the notification area
    // instead of tying it to a console window the operator has to leave open.
    #[cfg(windows)]
    if tray_mode {
        return crate::tray::run(server_started);
    }
    // `core_main()` already spawned the server thread for an argument-less run;
    // an explicit `--headless` reaches this point without one.
    status::run_host(server_started, verbose)
}

/// Whether this launch belongs in the notification area rather than a console.
///
/// Mirrors the dispatch below: no `--headless`, and either no subcommand at all or one of the
/// forms that mean "run the host" (`--tray`, `--relay-bridge`, a bare verbosity flag).
#[cfg(windows)]
fn is_tray_launch(args: &[String]) -> bool {
    if args.iter().any(|arg| arg == "--headless") {
        return false;
    }
    matches!(
        args.first().map(String::as_str),
        None | Some("--tray") | Some("--relay-bridge") | Some("-v") | Some("--verbose")
    )
}

/// What a `--relay-bridge` argument turned into.
#[derive(PartialEq, Eq)]
enum RelayRequest {
    Absent,
    Started,
    Invalid,
}

/// Starts the bridge when asked and removes its arguments, so the rest of the dispatch never
/// sees them. Returns whether the flag was present and whether it started.
fn take_relay_bridge(args: &mut Vec<String>) -> RelayRequest {
    let Some(position) = args.iter().position(|arg| arg == "--relay-bridge") else {
        return RelayRequest::Absent;
    };
    let Some(url) = args.get(position + 1).cloned() else {
        eprintln!("--relay-bridge needs the Worker URL");
        return RelayRequest::Invalid;
    };
    // An optional port follows; a non-numeric value there is left for the dispatcher so that
    // `--relay-bridge <url> --verbose` keeps working.
    let mut taken = 2;
    let mut peer_port = None;
    if let Some(value) = args.get(position + 2) {
        match value.parse::<u16>() {
            Ok(port) => {
                peer_port = Some(port);
                taken = 3;
            }
            Err(_) if value.starts_with("--") => {}
            Err(_) => {
                eprintln!("--relay-bridge <url> [peer-port]");
                return RelayRequest::Invalid;
            }
        }
    }
    match crate::relay_bridge::start(&url, peer_port) {
        Ok(where_to) => eprintln!("Relay bridge running: {where_to}"),
        Err(err) => {
            eprintln!("{err:#}");
            return RelayRequest::Invalid;
        }
    }
    args.drain(position..position + taken);
    RelayRequest::Started
}
