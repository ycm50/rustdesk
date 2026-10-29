//! Carries a RustDesk direct-server port to the relay Worker over WSS.
//!
//! The Worker pairs this socket with the viewer that connects to the websocket path and
//! forwards binary frames both ways. The peer own WSS transport is a sequence of binary
//! frames over a socket, so this bridge only moves bytes. The desktop peer is dialled on the
//! first frame a viewer sends, which keeps an idle bridge from holding a connection the peer
//! would time out.

use std::net::Ipv4Addr;
use std::time::Duration;

use anyhow::{Context, Result};
use futures_util::{SinkExt, StreamExt};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio_tungstenite::{tungstenite::Message, MaybeTlsStream};

const BUFFER_SIZE: usize = 32 * 1024;
const RECONNECT_DELAY: Duration = Duration::from_secs(3);
const DEFAULT_PEER_PORT: u16 = 21118;

/// Starts the bridge on its own thread and returns immediately.
///
/// The host loads this library only when `--relay-bridge` is passed, so a host that does not
/// relay never maps this code at all.
///
/// # Safety
/// `url` must point to a NUL-terminated UTF-8 string that stays valid for the call.
#[no_mangle]
pub unsafe extern "C" fn rustdesk_relay_bridge_start(
    url: *const std::os::raw::c_char,
    peer_port: u16,
) -> i32 {
    if url.is_null() {
        return 2;
    }
    let url = match unsafe { std::ffi::CStr::from_ptr(url) }.to_str() {
        Ok(url) if url.starts_with("ws://") || url.starts_with("wss://") => url.to_owned(),
        _ => return 2,
    };
    let peer_port = if peer_port == 0 {
        DEFAULT_PEER_PORT
    } else {
        peer_port
    };

    std::thread::Builder::new()
        .name("relay-bridge".to_owned())
        .spawn(move || {
            // A dedicated runtime: the caller is another process, so there is no runtime
            // here to borrow, and creating one inside this thread keeps it off the host.
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(e) => {
                    eprintln!("[bridge] cannot start runtime: {e}");
                    return;
                }
            };
            runtime.block_on(run_forever(url, peer_port));
        })
        .is_ok() as i32
}

async fn run_forever(url: String, peer_port: u16) {
    // rustls 0.23 refuses to pick a provider on its own when several are compiled in.
    if rustls::crypto::ring::default_provider()
        .install_default()
        .is_err()
    {
        eprintln!("[bridge] crypto provider already installed");
    }
    let options = Options {
        url,
        peer_port,
    };
    println!("[bridge] {} <-> 127.0.0.1:{}", options.url, options.peer_port);
    loop {
        if let Err(e) = run_session(&options).await {
            eprintln!("[bridge] session ended: {e:#}");
        }
        tokio::time::sleep(RECONNECT_DELAY).await;
    }
}

/// Pumps one viewer session between the relay and the desktop peer.
///
/// The peer is dialled on the first frame the viewer sends, not at startup: the host drops a
/// direct connection that stays silent for 30s, so an idle bridge must not hold one open. If
/// the peer goes away mid-session (that same idle timeout), the next frame dials it again
/// instead of tearing down the viewer's socket.
async fn run_session(options: &Options) -> Result<()> {
    let socket = connect_relay(options.url.as_str()).await?;
    let (mut sink, mut stream) = socket.split();
    let (frame_tx, mut frame_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(8);
    // A viewer arriving has to force a fresh greeting from the peer, so the relay tells us.
    let redial = std::sync::Arc::new(tokio::sync::Notify::new());
    let redial_hint = redial.clone();
    // Set when the viewer that the current peer connection was opened for goes away.
    let stale = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let stale_hint = stale.clone();

    // The relay side only feeds the queue: who is connected to the peer is the other half's
    // business, so a reconnect never costs the viewer its socket.
    let relay_reader = async move {
        while let Some(message) = stream.next().await {
            let message = message.context("relay read")?;
            let bytes = match message {
                Message::Binary(bytes) => bytes,
                // Text is a relay control frame, never peer traffic.
                Message::Text(text) => {
                    match text.trim() {
                        "viewer-up" => redial_hint.notify_one(),
                        "viewer-down" => {
                            stale_hint.store(true, std::sync::atomic::Ordering::Release);
                            redial_hint.notify_one();
                        }
                        _ => {}
                    }
                    continue;
                }
                Message::Close(frame) => {
                    // Who closed the relay leg and why: 1009 is Cloudflare rejecting a message
                    // (too big), 1006 is an abnormal drop, 1000 is a normal close.
                    match frame {
                        Some(frame) => eprintln!(
                            "[bridge] relay closed by peer: code {} reason {:?}",
                            frame.code, frame.reason
                        ),
                        None => eprintln!("[bridge] relay closed by peer: no close frame"),
                    }
                    break;
                }
                _ => continue,
            };
            if bytes.is_empty() {
                continue;
            }
            if frame_tx.send(bytes.to_vec()).await.is_err() {
                break;
            }
        }
        Ok::<(), anyhow::Error>(())
    };

    let peer_pump = async {
        let mut buffer = vec![0u8; BUFFER_SIZE];
        loop {
            match TcpStream::connect((Ipv4Addr::LOCALHOST, options.peer_port)).await {
                Ok(peer) => {
                    println!("[bridge] peer connected on {}", options.peer_port);
                    let (mut peer_read, mut peer_write) = peer.into_split();
                    let mut to_peer_frames = 0u64;
                    let mut from_peer_bytes = 0u64;
                    let mut pending: Vec<u8> = Vec::with_capacity(BUFFER_SIZE);
                    loop {
                        tokio::select! {
                            // Created per iteration: Notified is !Unpin, and notify_one keeps a
                            // permit for the next waiter, so no wake-up is lost.
                            // Only a connection the viewer has not used yet is replaced: breaking
                            // a live one would cut the session just after it logged in.
                            _ = redial.notified() => {
                                // Drop the connection when the next viewer has nothing to reuse:
                                // either nothing was sent yet, or the viewer it was opened for
                                // is gone (its greeting has already been spent).
                                if to_peer_frames == 0 || stale.swap(false, std::sync::atomic::Ordering::AcqRel) {
                                    break;
                                }
                            }
                            frame = frame_rx.recv() => {
                                let Some(bytes) = frame else { return Ok(()) };
                                to_peer_frames += 1;
                                // The peer reads length-prefixed frames; a WebSocket frame carries
                                // the bare message, so the prefix has to be rebuilt here.
                                peer_write
                                    .write_all(&frame_header_for(bytes.len()))
                                    .await
                                    .context("write frame header")?;
                                peer_write.write_all(&bytes).await.context("write to peer")?;
                                peer_write.flush().await.context("flush to peer")?;
                            }
                            read = peer_read.read(&mut buffer) => {
                                let read = read.context("read from peer")?;
                                if read > 0 {
                                    pending.extend_from_slice(&buffer[..read]);
                                    // Strip the length prefix: the viewer expects one message per
                                    // WebSocket frame, without the peer's framing.
                                    loop {
                                        let Some((head_len, len)) = frame_header(&pending) else { break };
                                        if pending.len() < head_len + len { break }
                                        let payload: Vec<u8> = pending[head_len..head_len + len].to_vec();
                                        pending.drain(..head_len + len);
                                        from_peer_bytes += payload.len() as u64;
                                        sink.send(Message::Binary(payload.into()))
                                            .await
                                            .context("send to relay")?;
                                    }
                                    continue;
                                }
                                if read == 0 {
                                    // The host closes a silent connection after its idle timeout,
                                    // so redial at once: the replacement carries a fresh opening
                                    // message for whichever viewer is paired by then.
                                    // Only worth a line when a viewer was actually talking:
                                    // an idle bridge redials every 30s forever.
                                    if to_peer_frames > 0 {
                                        eprintln!(
                                            "[bridge] peer session ended: viewer sent {} frames, peer sent {} bytes",
                                            to_peer_frames, from_peer_bytes
                                        );
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
                Err(e) => eprintln!("[bridge] cannot reach the peer: {e}"),
            }
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    };

    tokio::select! {
        result = relay_reader => result,
        result = peer_pump => result,
    }
}

/// Reads one RustDesk frame header: the low two bits of the first byte give its width, the
/// value is little endian, and the top two bits are the length.
fn frame_header(bytes: &[u8]) -> Option<(usize, usize)> {
    let first = *bytes.first()?;
    let head_len = ((first & 0x3) as usize) + 1;
    if bytes.len() < head_len {
        return None;
    }
    let mut value = first as usize;
    for (index, byte) in bytes.iter().enumerate().take(head_len).skip(1) {
        value |= (*byte as usize) << (8 * index);
    }
    Some((head_len, value >> 2))
}

/// The header the peer expects in front of a message of `len` bytes.
fn frame_header_for(len: usize) -> Vec<u8> {
    let head_len = if len >= 1 << 22 {
        4
    } else if len >= 1 << 14 {
        3
    } else if len >= 1 << 6 {
        2
    } else {
        1
    };
    let value = (len << 2) | (head_len - 1);
    (0..head_len).map(|index| (value >> (8 * index)) as u8).collect()
}

/// Opens the relay socket directly. The DLL entry takes no proxy: an inherited HTTPS_PROXY
/// would silently reroute a relay leg that normally reaches the Worker on its own.
async fn connect_relay(url: &str) -> Result<tokio_tungstenite::WebSocketStream<MaybeTlsStream<TcpStream>>> {
    tokio_tungstenite::connect_async(url)
        .await
        .map(|(socket, _)| socket)
        .with_context(|| format!("connect {url}"))
}

struct Options {
    url: String,
    peer_port: u16,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entry_rejects_non_websocket_urls() {
        let url = std::ffi::CString::new("http://example.com").unwrap();
        assert_eq!(unsafe { rustdesk_relay_bridge_start(url.as_ptr(), 0) }, 2);
    }

    #[test]
    fn entry_rejects_a_null_url() {
        assert_eq!(unsafe { rustdesk_relay_bridge_start(std::ptr::null(), 0) }, 2);
    }
}
