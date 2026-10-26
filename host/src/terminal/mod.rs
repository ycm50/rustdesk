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

pub fn run(args: &mut [String]) -> i32 {
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
            // No tray exists in this build; it is reached from `core_main()` on
            // autostart runs, so accept it quietly instead of failing.
            "--tray" => {}
            "--headless" | "-v" | "--verbose" => {}
            other => {
                eprintln!("Unknown argument: {other}");
                eprint!("{USAGE}");
                return 2;
            }
        }
    }
    let verbose = args.iter().any(|arg| arg == "-v" || arg == "--verbose");
    // `core_main()` already spawned the server thread for an argument-less run;
    // an explicit `--headless` reaches this point without one.
    status::run_host(args.is_empty(), verbose)
}
