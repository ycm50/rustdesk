//! Headless connection manager.
//!
//! When a file-transfer (or port-forward/RDP) session starts, the host connects
//! to the `_cm` IPC and treats that stream as the operator's window: session
//! events go there, authorization comes back, and the session ends when the
//! stream does. With no window nobody can click accept, so this process answers
//! for the user, keeps the stream open for as long as the session lives, and
//! performs the filesystem work the host delegates here (directory listings and
//! writing received files).
//!
//! The host spawns it as `rustdesk --cm`, so it has to stay resident.

use std::path::PathBuf;

use base::{
    fs::{self, DigestCheckResult},
    message_proto::*,
};
use hbb_common::{config::Config, log, protobuf::Message as _, tokio};

use crate::ipc::{self, Connection, Data};

#[tokio::main(flavor = "current_thread")]
pub async fn run() -> i32 {
    let mut incoming = match ipc::new_listener("_cm").await {
        Ok(incoming) => incoming,
        Err(err) => {
            log::error!("cannot listen on the cm ipc: {}", err);
            return 1;
        }
    };
    log::info!("connection manager listening");
    while let Some(result) = incoming.next().await {
        match result {
            Ok(stream) => {
                tokio::spawn(serve(Connection::new(stream)));
            }
            Err(err) => log::error!("cm accept failed: {}", err),
        }
    }
    0
}

async fn serve(mut conn: Connection) {
    let mut write_jobs: Vec<fs::TransferJob> = Vec::new();
    loop {
        match conn.next().await {
            Ok(Some(data)) => {
                if !handle(&mut conn, data, &mut write_jobs).await {
                    log::info!("cm session closed by request");
                    return;
                }
            }
            Ok(None) => {
                log::info!("cm session ended (stream closed)");
                return;
            }
            Err(err) => {
                log::error!("cm connection failed: {}", err);
                return;
            }
        }
    }
}

/// Returns false when the session is over.
async fn handle(conn: &mut Connection, data: Data, write_jobs: &mut Vec<fs::TransferJob>) -> bool {
    match data {
        Data::Login {
            id,
            peer_id,
            authorized,
            is_file_transfer,
            is_terminal,
            ..
        } => {
            log::info!(
                "session {id} from {peer_id}: file transfer {is_file_transfer}, terminal {is_terminal}, authorized {authorized}"
            );
            // No window means no one to ask; a peer that already passed
            // authentication is accepted.
            if !authorized {
                if let Err(err) = conn.send(&Data::Authorize).await {
                    log::error!("cm authorize failed: {}", err);
                    return false;
                }
            }
        }
        Data::FileTransferLog((action, text)) => log::info!("file transfer {action}: {text}"),
        Data::FS(message) => handle_fs(conn, message, write_jobs).await,
        Data::Close => return false,
        _ => {}
    }
    true
}

async fn handle_fs(conn: &mut Connection, message: ipc::FS, write_jobs: &mut Vec<fs::TransferJob>) {
    match message {
        ipc::FS::ReadDir { dir, include_hidden } => {
            reply_read_dir(conn, &dir, include_hidden).await;
        }
        ipc::FS::NewWrite {
            path,
            id,
            file_num,
            mut files,
            overwrite_detection,
            total_size,
            conn_id,
        } => {
            let entries: Vec<FileEntry> = files
                .drain(..)
                .map(|(name, modified_time)| FileEntry {
                    name,
                    modified_time,
                    ..Default::default()
                })
                .collect();
            let mut job = fs::TransferJob::new_write(
                id,
                fs::JobType::Generic,
                String::new(),
                fs::DataSource::FilePath(PathBuf::from(&path)),
                file_num,
                false,
                false,
                overwrite_detection,
            );
            if let Err(err) = job.set_files(entries) {
                log::warn!("reject transfer file list for {}: {}", path, err);
                send_raw(conn, fs::new_error(id, err, file_num)).await;
                return;
            }
            job.total_size = total_size;
            job.conn_id = conn_id;
            write_jobs.push(job);
        }
        ipc::FS::WriteBlock {
            id,
            file_num,
            data: _,
            compressed,
        } => {
            // The payload travels as its own raw frame right after the message;
            // the `data` field is only a placeholder.
            let raw = match conn.next_raw().await {
                Ok(raw) => raw,
                Err(err) => {
                    log::error!("cm read block payload failed: {}", err);
                    return;
                }
            };
            if let Some(job) = fs::get_job(id, write_jobs) {
                let block = FileTransferBlock {
                    id,
                    file_num,
                    data: raw.into(),
                    compressed,
                    ..Default::default()
                };
                if let Err(err) = job.write(block).await {
                    send_raw(conn, fs::new_error(id, err, file_num)).await;
                }
            }
        }
        ipc::FS::WriteDone { id, file_num } => {
            if let Some(job) = fs::remove_job(id, write_jobs) {
                job.modify_time();
                send_raw(conn, fs::new_done(id, file_num)).await;
            }
        }
        ipc::FS::WriteError { id, file_num, err } => {
            if let Some(job) = fs::remove_job(id, write_jobs) {
                send_raw(conn, fs::new_error(job.id(), err, file_num)).await;
            }
        }
        ipc::FS::CancelWrite { id } => {
            if let Some(job) = fs::remove_job(id, write_jobs) {
                job.remove_download_file();
            }
        }
        ipc::FS::CheckDigest {
            id,
            file_num,
            file_size,
            last_modified,
            is_upload,
            is_resume,
        } => {
            check_digest(
                conn,
                write_jobs,
                id,
                file_num,
                file_size,
                last_modified,
                is_upload,
                is_resume,
            )
            .await;
        }
        ipc::FS::SendConfirm(bytes) => {
            if let Ok(request) = FileTransferSendConfirmRequest::parse_from_bytes(&bytes) {
                if let Some(job) = fs::get_job(request.id, write_jobs) {
                    job.confirm(&request).await;
                }
            }
        }
        _ => {}
    }
}

#[allow(clippy::too_many_arguments)]
async fn check_digest(
    conn: &mut Connection,
    write_jobs: &mut [fs::TransferJob],
    id: i32,
    file_num: i32,
    file_size: u64,
    last_modified: u64,
    is_upload: bool,
    is_resume: bool,
) {
    let Some(job) = fs::get_job(id, write_jobs) else {
        return;
    };
    let mut request = FileTransferSendConfirmRequest {
        id,
        file_num,
        union: Some(file_transfer_send_confirm_request::Union::OffsetBlk(0)),
        ..Default::default()
    };
    let digest = FileTransferDigest {
        id,
        file_num,
        last_modified,
        file_size,
        ..Default::default()
    };
    let Some(file) = job.files().get(file_num as usize) else {
        return;
    };
    let fs::DataSource::FilePath(base) = &job.data_source else {
        return;
    };
    let path = fs::get_string(&fs::TransferJob::join(base, &file.name));
    match fs::is_write_need_confirmation(is_resume, &path, &digest) {
        Ok(result) => {
            job.set_digest(file_size, last_modified);
            match result {
                DigestCheckResult::IsSame => {
                    request.set_skip(true);
                    send_raw(conn, fs::new_send_confirm(request)).await;
                }
                DigestCheckResult::NeedConfirm(mut digest) => {
                    digest.is_upload = is_upload;
                    let mut message = Message::new();
                    let mut response = FileResponse::new();
                    response.set_digest(digest);
                    message.set_file_response(response);
                    send_raw(conn, message).await;
                }
                DigestCheckResult::NoSuchFile => {
                    send_raw(conn, fs::new_send_confirm(request)).await;
                }
            }
        }
        Err(err) => send_raw(conn, fs::new_error(id, err, file_num)).await,
    }
}

async fn reply_read_dir(conn: &mut Connection, dir: &str, include_hidden: bool) {
    let path = if dir.is_empty() {
        Config::get_home()
    } else {
        fs::get_path(dir)
    };
    let message = match fs::read_dir(&path, include_hidden) {
        Ok(fd) => {
            let mut message = Message::new();
            let mut response = FileResponse::new();
            response.set_dir(fd);
            message.set_file_response(response);
            message
        }
        Err(err) => fs::new_error(0, err, -1),
    };
    send_raw(conn, message).await;
}

async fn send_raw(conn: &mut Connection, message: Message) {
    match message.write_to_bytes() {
        Ok(bytes) => {
            if let Err(err) = conn.send(&Data::RawMessage(bytes)).await {
                log::error!("cm send failed: {}", err);
            }
        }
        Err(err) => log::error!("cm serialize failed: {}", err),
    }
}
