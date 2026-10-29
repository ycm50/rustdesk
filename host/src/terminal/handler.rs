//! Session callbacks for the terminal front end.
//!
//! A terminal session has no window: the handler only forwards what the peer
//! sends to stdout, and everything the UI would draw is dropped. Connection
//! state is exposed as flags so the caller's loop can react without a channel.

use std::{
    ptr,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    time::Instant,
};

use base::message_proto::*;
use hbb_common::{log, rendezvous_proto::ConnType};

use crate::{
    client::*,
    terminal::{console, render::Renderer},
    ui_session_interface::InvokeUiSession,
};

/// Everything the screen pane needs while a frame is on screen.
pub struct ScreenState {
    pub renderer: Renderer,
    /// Size of the last received frame, in pixels.
    pub remote: (usize, usize),
    pub started: Instant,
}

/// The peer asked whether an existing file may be overwritten; without a window
/// there is nobody to ask, so the caller answers on the user's behalf.
pub struct ConfirmRequest {
    pub id: i32,
    pub file_num: i32,
    pub is_upload: bool,
}

#[derive(Clone, Default)]
pub struct JobState {
    pub done: bool,
    pub file_num: i32,
    pub finished_size: f64,
    pub speed: f64,
    pub error: Option<String>,
}

#[derive(Clone, Default)]
pub struct TerminalHandler {
    connected: Arc<AtomicBool>,
    closed: Arc<AtomicBool>,
    folder: Arc<Mutex<Option<(String, Vec<FileEntry>)>>>,
    job: Arc<Mutex<JobState>>,
    confirm: Arc<Mutex<Option<mpsc::Sender<ConfirmRequest>>>>,
    screen: Arc<Mutex<Option<ScreenState>>>,
}

impl TerminalHandler {
    /// Install the renderer used by `--connect`.
    pub fn set_screen(&self, renderer: Renderer) {
        if let Ok(mut screen) = self.screen.lock() {
            *screen = Some(ScreenState {
                renderer,
                remote: (0, 0),
                started: Instant::now(),
            });
        }
    }

    pub fn screen_size(&self) -> (usize, usize) {
        self.screen
            .lock()
            .ok()
            .and_then(|screen| screen.as_ref().map(|state| state.renderer.size()))
            .unwrap_or((0, 0))
    }

    pub fn set_screen_size(&self, cols: usize, rows: usize) {
        if let Ok(mut screen) = self.screen.lock() {
            if let Some(state) = screen.as_mut() {
                state.renderer.set_size(cols, rows);
            }
        }
    }

    /// Size of the last frame the peer sent, in pixels.
    pub fn remote_size(&self) -> (usize, usize) {
        self.screen
            .lock()
            .ok()
            .and_then(|screen| screen.as_ref().map(|state| state.remote))
            .unwrap_or((0, 0))
    }

    pub fn rendered_frames(&self) -> u64 {
        self.screen
            .lock()
            .ok()
            .and_then(|screen| screen.as_ref().map(|state| state.renderer.frames))
            .unwrap_or(0)
    }

    /// Install the channel used to answer overwrite questions from the peer.
    pub fn set_confirm_sender(&self, sender: mpsc::Sender<ConfirmRequest>) {
        if let Ok(mut confirm) = self.confirm.lock() {
            *confirm = Some(sender);
        }
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::SeqCst)
    }

    /// True once the peer closed the terminal or reported an error.
    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// Last directory listing the peer sent, with its path.
    pub fn take_folder(&self) -> Option<(String, Vec<FileEntry>)> {
        self.folder.lock().ok()?.take()
    }

    pub fn job(&self) -> Option<JobState> {
        self.job.lock().ok().map(|job| job.clone())
    }
}

impl InvokeUiSession for TerminalHandler {
    fn set_cursor_data(&self, _cd: CursorData) {}

    fn set_cursor_id(&self, _id: String) {}

    fn set_cursor_position(&self, _cp: CursorPosition) {}

    fn set_display(&self, _x: i32, _y: i32, _w: i32, _h: i32, _cursor_embedded: bool, _scale: f64) {}

    fn switch_display(&self, _display: &SwitchDisplay) {}

    fn set_peer_info(&self, _peer_info: &PeerInfo) {}

    fn set_displays(&self, _displays: &Vec<DisplayInfo>) {}

    fn set_platform_additions(&self, _data: &str) {}

    fn on_connected(&self, _conn_type: ConnType) {
        self.connected.store(true, Ordering::SeqCst);
    }

    fn update_privacy_mode(&self) {}

    fn set_permission(&self, _name: &str, _value: bool) {}

    fn close_success(&self) {}

    fn update_quality_status(&self, _qs: QualityStatus) {}

    fn set_connection_type(&self, _is_secured: bool, _direct: bool, _stream_type: &str) {}

    fn set_fingerprint(&self, _fingerprint: String) {}

    fn job_error(&self, id: i32, err: String, file_num: i32) {
        log::error!("file job {id} (file {file_num}) failed: {err}");
        if let Ok(mut job) = self.job.lock() {
            job.done = true;
            job.error = Some(err);
        }
    }

    fn job_done(&self, id: i32, file_num: i32) {
        log::info!("file job {id} done, {file_num} file(s)");
        if let Ok(mut job) = self.job.lock() {
            job.done = true;
            job.file_num = file_num;
        }
    }

    fn clear_all_jobs(&self) {}

    fn new_message(&self, msg: String) {
        log::info!("{msg}");
    }

    fn update_transfer_list(&self) {}

    fn load_last_job(&self, _cnt: i32, _job_json: &str, _auto_start: bool) {}

    fn update_folder_files(
        &self,
        _id: i32,
        entries: &Vec<FileEntry>,
        path: String,
        _is_local: bool,
        only_count: bool,
    ) {
        if only_count {
            return;
        }
        if let Ok(mut folder) = self.folder.lock() {
            *folder = Some((path, entries.clone()));
        }
    }

    fn confirm_delete_files(&self, _id: i32, _i: i32, _name: String) {}

    fn override_file_confirm(
        &self,
        id: i32,
        file_num: i32,
        _to: String,
        is_upload: bool,
        _is_identical: bool,
    ) {
        if let Ok(confirm) = self.confirm.lock() {
            if let Some(sender) = confirm.as_ref() {
                let _ = sender.send(ConfirmRequest {
                    id,
                    file_num,
                    is_upload,
                });
            }
        }
    }

    fn update_block_input_state(&self, _on: bool) {}

    fn job_progress(&self, _id: i32, file_num: i32, speed: f64, finished_size: f64) {
        if let Ok(mut job) = self.job.lock() {
            job.file_num = file_num;
            job.speed = speed;
            job.finished_size = finished_size;
        }
    }

    fn adapt_size(&self) {}

    fn on_rgba(&self, _display: usize, rgba: &mut scrap::ImageRgb) {
        let Ok(mut screen) = self.screen.lock() else {
            return;
        };
        let Some(state) = screen.as_mut() else {
            return;
        };
        state.remote = (rgba.w, rgba.h);
        let elapsed = state.started.elapsed().as_secs_f64().max(0.1);
        let status = format!(
            " RustDesk | {:.0} fps | {} KB | Ctrl+] quit ",
            state.renderer.frames as f64 / elapsed,
            state.renderer.bytes / 1024
        );
        if let Err(err) = state.renderer.render(rgba, &status) {
            log::error!("render failed: {}", err);
        }
    }

    fn msgbox(&self, msgtype: &str, title: &str, text: &str, link: &str, _retry: bool) {
        log::error!("{msgtype}: {title} {text} {link}");
        eprintln!("{title}: {text}");
    }

    fn cancel_msgbox(&self, _tag: &str) {}

    fn switch_back(&self, _id: &str) {}

    fn portable_service_running(&self, _running: bool) {}

    fn on_voice_call_started(&self) {}

    fn on_voice_call_closed(&self, _reason: &str) {}

    fn on_voice_call_waiting(&self) {}

    fn on_voice_call_incoming(&self) {}

    fn get_rgba(&self, _display: usize) -> *const u8 {
        ptr::null()
    }

    fn next_rgba(&self, _display: usize) {}

    fn set_multiple_windows_session(&self, _sessions: Vec<WindowsSession>) {}

    fn set_current_display(&self, _disp_idx: i32) {}

    fn update_record_status(&self, _start: bool) {}

    fn printer_request(&self, _id: i32, _path: String) {}

    fn handle_screenshot_resp(&self, _sid: String, _msg: String) {}

    fn handle_terminal_response(&self, response: TerminalResponse) {
        match response.union {
            Some(terminal_response::Union::Data(data)) => {
                let bytes = if data.compressed {
                    hbb_common::compress::decompress(&data.data)
                } else {
                    data.data.to_vec()
                };
                if let Err(err) = console::write_stdout(&bytes) {
                    log::error!("write stdout failed: {}", err);
                }
            }
            Some(terminal_response::Union::Opened(opened)) => {
                log::info!("terminal opened, pid {}", opened.pid);
            }
            Some(terminal_response::Union::Closed(closed)) => {
                log::info!("terminal closed, exit code {}", closed.exit_code);
                self.closed.store(true, Ordering::SeqCst);
            }
            Some(terminal_response::Union::Error(error)) => {
                log::error!("terminal error: {}", error.message);
                self.closed.store(true, Ordering::SeqCst);
            }
            Some(_) => {}
            None => {}
        }
    }
}
