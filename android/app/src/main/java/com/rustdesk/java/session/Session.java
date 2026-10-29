package com.rustdesk.java.session;

import com.google.protobuf.ByteString;
import com.rustdesk.java.codec.VideoDecoder;
import com.rustdesk.java.net.RelayClient;
import com.rustdesk.java.net.RendezvousClient;
import com.rustdesk.java.net.StreamTransport;
import com.rustdesk.java.net.WsTransport;
import com.rustdesk.java.net.Transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

import hbb.MessageOuterClass.EncodedVideoFrame;
import hbb.MessageOuterClass.EncodedVideoFrames;
import hbb.MessageOuterClass.Hash;
import hbb.MessageOuterClass.ImageQuality;
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.LoginResponse;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.Misc;
import hbb.MessageOuterClass.SwitchDisplay;
import hbb.MessageOuterClass.TerminalClosed;
import hbb.MessageOuterClass.TerminalData;
import hbb.MessageOuterClass.TerminalError;
import hbb.MessageOuterClass.TerminalResponse;
import hbb.MessageOuterClass.TestDelay;
import hbb.MessageOuterClass.VideoFrame;

/**
 * One viewer session: resolve the peer through rendezvous, run the key exchange, answer the
 * password challenge and pump video frames into the decoder.
 */
public class Session implements Runnable {

    /** Connection attempts before the UI is told the session failed. */
    private static final int MAX_ATTEMPTS = 3;

    /** Undecodable messages tolerated before the stream is treated as corrupt. */
    private static final int MAX_PARSE_ERRORS = 5;

    /** Keepalive probe period once the session is logged in. */
    private static final long KEEPALIVE_INTERVAL_MS = 5000L;

    /** No inbound traffic for this long is treated as a dead link. */
    private static final long WATCHDOG_TIMEOUT_MS = 25000L;

    /** How often the watchdog thread re-evaluates the link. */
    private static final long KEEPALIVE_TICK_MS = 1000L;

    /** Delays used after a successful login; the last entry repeats. */
    private static final int[] RECONNECT_DELAYS_MS = {1000, 2000, 4000, 8000, 15000};

    /** Receives frames decoded from the wire. */
    public interface Listener {
        void onStatus(String status);

        void onPeerInfo(String platform, int width, int height);

        void onClosed(String reason);

        /** Text the peer put on its clipboard. Called on the session thread. */
        void onClipboardText(String text);

        /** Output bytes read from the peer's terminal. Called on the session thread. */
        void onTerminalData(int terminalId, byte[] bytes);

        void onTerminalClosed(int terminalId, int exitCode);
    }

    private final String peerId;
    private final String password;
    private final String rendezvousServer;
    private final int rendezvousPort;
    private final VideoDecoder decoder;
    private final DeviceIdentity identity;
    private final Listener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Transport transport;
    /** Bounded so a stalled peer cannot grow the queue without limit. */
    private final java.util.concurrent.BlockingQueue<byte[]> outbox =
            new java.util.concurrent.LinkedBlockingQueue<byte[]>(256);
    private volatile Thread senderThread;
    private volatile int displayWidth;
    private volatile int displayHeight;
    private volatile long framesReceived;
    private volatile long bytesReceived;
    /** Codec id of the last video frame, as understood by {@link VideoDecoder#mimeFor(int)}. */
    private volatile int lastVideoMime;
    /** How the current connection was made: "direct", "relay", or null before either works. */
    private volatile String connectPath;
    /**
     * Username to put in LoginRequest when the peer is reached through a WebSocket tunnel.
     *
     * <p>The host only accepts a direct connection when the login username looks like an IP or a
     * domain with a port (src/common.rs:is_direct_ip_access). A bare tunnel hostname fails that
     * check and the host closes the stream, so the tunnel path sends "host:443" instead.
     */
    private volatile String tunnelUsername;
    /** Video parameters requested by the UI; survives reconnects. */
    private volatile VideoSettings videoSettings = VideoSettings.defaults();
    /** Platform string of the peer, kept so a display change can re-announce its size. */
    private volatile String peerPlatform = "";
    /** True between a successful login and the end of the current connection. */
    private volatile boolean loggedIn;
    /** True once any login succeeded; makes later reconnects unlimited. */
    private volatile boolean everLoggedIn;
    /** {@link System#nanoTime()} of the last successfully parsed inbound message. */
    private volatile long lastInboundNanos;
    /** {@link System#nanoTime()} of the last keepalive probe. */
    private volatile long lastPingNanos;
    /** The thread running {@link #run()}, so stop() can interrupt a reconnect sleep. */
    private volatile Thread sessionThread;
    /** Index into {@link #RECONNECT_DELAYS_MS}; reset by every successful login. */
    private int backoffIndex;

    public Session(String peerId, String password, String rendezvousServer, int rendezvousPort,
                   DeviceIdentity identity, VideoDecoder decoder, Listener listener) {
        this.peerId = peerId;
        this.password = password == null ? "" : password;
        this.rendezvousServer = rendezvousServer;
        this.rendezvousPort = rendezvousPort;
        this.identity = identity;
        this.decoder = decoder;
        this.listener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread t = new Thread(this, "session");
        sessionThread = t;
        t.start();
    }

    public void stop() {
        running.set(false);
        loggedIn = false;
        // Wake the sender out of take() so it can finish; the empty array is the shutdown marker.
        outbox.offer(new byte[0]);
        Thread worker = sessionThread;
        sessionThread = null;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
        Transport t = transport;
        transport = null;
        if (t != null) t.close();
    }

    /**
     * Queues an already built message; ignored until the connection exists.
     *
     * <p>The write itself happens on the sender thread. Android throws
     * {@code NetworkOnMainThreadException} for socket writes on the UI thread, and every input
     * callback runs there, so writing inline here crashed the app on the first touch.
     *
     * @return true when the message was queued
     */
    public boolean sendMessage(Message message) {
        if (transport == null || !running.get()) return false;
        return outbox.offer(InputMessages.stamp(message).toByteArray());
    }

    /** Queues one message on the sender thread; null is ignored. */
    private boolean enqueue(Message message) {
        if (message == null || transport == null || !running.get()) return false;
        return outbox.offer(message.toByteArray());
    }

    /**
     * Queues a link-control message (latency probe echo, keepalive). If pointer input has filled
     * the queue, drop the oldest input message instead of the probe: a missing probe reply makes
     * the host halve its frame rate, while losing one touch sample is harmless.
     */
    private boolean enqueueControl(Message message) {
        if (message == null || transport == null || !running.get()) return false;
        byte[] bytes = message.toByteArray();
        if (outbox.offer(bytes)) return true;
        outbox.poll();
        return outbox.offer(bytes);
    }

    private void startSender(final Transport t) {
        // Stop the sender of the connection just closed before a new one starts, so it cannot
        // steal a payload from the shared queue and then exit on the transport check.
        Thread previous = senderThread;
        if (previous != null && previous != Thread.currentThread()) {
            previous.interrupt();
        }
        outbox.clear();
        Thread thread = new Thread(() -> senderLoop(t), "rustdesk-sender");
        thread.setDaemon(true);
        senderThread = thread;
        thread.start();
    }

    /** Drains {@link #outbox} onto {@code t}; exits when the queue is poisoned by stop(). */
    private void senderLoop(final Transport t) {
        while (running.get()) {
            byte[] payload;
            try {
                payload = outbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (payload.length == 0) {
                return;                       // shutdown marker
            }
            if (transport != t) {
                return;                       // a newer connection owns the session
            }
            try {
                t.send(payload);
            } catch (IOException e) {
                status("send failed: " + e.getMessage());
                // Closing unblocks the reader so the reconnect loop can take over.
                t.close();
                return;
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getFramesReceived() {
        return framesReceived;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    /** Applies new video parameters; sent immediately when the session is logged in. */
    public void setVideoSettings(VideoSettings s) {
        if (s == null) return;
        videoSettings = s;
        if (!loggedIn) return;
        enqueue(s.toFpsMessage());
        if (s.hasCustomScale()) {
            enqueue(s.toScaleMessage());
        }
    }

    public VideoSettings getVideoSettings() {
        return videoSettings;
    }

    /** Changes the requested frame rate, keeping the current stream scale. */
    public void setVideoFps(int fps) {
        videoSettings = videoSettings.withFps(fps);
        if (loggedIn) {
            enqueue(videoSettings.toFpsMessage());
        }
    }

    /**
     * Asks the host to encode and transmit the stream at {@code scalePercent} of the captured
     * size. The controlled desktop keeps its own resolution; 100 restores the native size.
     */
    /** Changes the image quality (host bitrate/quality ratio); QoS adapts around it. */
    public void setVideoQuality(ImageQuality quality) {
        videoSettings = videoSettings.withQuality(quality);
        if (loggedIn) {
            enqueue(videoSettings.toQualityMessage());
        }
    }

    public void setVideoScale(int scalePercent) {
        videoSettings = videoSettings.withScale(scalePercent);
        if (loggedIn) {
            enqueue(videoSettings.toScaleMessage());
            // Some vendor decoders reject an in-band size change (Qualcomm VP9 logs
            // "Unsupported input buffer"), so tell this one the new stream size up front.
            if (lastVideoMime != 0) {
                applyDecoderSize(VideoDecoder.mimeFor(lastVideoMime));
            }
        }
    }

    /**
     * Hands the decoder the size the host will actually transmit. Only the software codecs are
     * scaled on the host (H.264/H.265 hardware encoders keep the captured size), and the host
     * rounds each side down to an even number no smaller than 16.
     */
    private void applyDecoderSize(String mime) {
        if (decoder == null || mime == null) return;
        int width = displayWidth;
        int height = displayHeight;
        if (videoSettings.hasCustomScale() && lastVideoMime >= 3) {
            width = scaledSide(displayWidth, videoSettings.scalePercent());
            height = scaledSide(displayHeight, videoSettings.scalePercent());
        }
        decoder.setMime(mime, width, height);
    }

    private static int scaledSide(int nativeSide, int scalePercent) {
        return Math.max(16, (nativeSide * scalePercent / 100) & ~1);
    }

    /**
     * Turns a tunnel address into a WebSocket URL. A bare hostname is assumed to be HTTPS, since
     * that is what a Cloudflare tunnel hands out.
     */
    private static String normalizeTunnelUrl(String peerId) {
        if (peerId == null) return null;
        String value = peerId.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("wss://") || lower.startsWith("ws://")) return value;
        if (lower.startsWith("https://")) return "wss://" + value.substring(8);
        if (lower.startsWith("http://")) return "ws://" + value.substring(7);
        // host:port with no scheme is a plain TCP endpoint, not a tunnel URL: the host speaks
        // length-prefixed frames on 21118 and a raw TCP forward (an frp-style tunnel, or a named
        // LAN machine) keeps them, while the WebSocket path expects one bare message per frame.
        if (explicitPort(value) > 0) return null;
        // A hostname rather than a numeric id: the id rules already reject these, so a dotted name
        // here can only mean a tunnel endpoint.
        if (lower.contains(".") && !lower.matches("[0-9.]+")) return "wss://" + value;
        return null;
    }

    /** @return the port in "host:port", or 0 when the value carries no usable one */
    private static int explicitPort(String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return 0;
        try {
            int port = Integer.parseInt(value.substring(colon + 1));
            return port > 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Derives the login username for a tunnel URL: the host part plus the port the tunnel served on,
     * because a bare hostname is rejected by the host's direct-access check.
     */
    private static String tunnelUserFromUrl(String url) {
        String rest = url;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) rest = rest.substring(scheme + 3);
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int colon = rest.lastIndexOf(':');
        if (colon > 0) {
            return rest;                       // already carries a port
        }
        boolean secure = url.toLowerCase(java.util.Locale.ROOT).startsWith("wss://")
                || url.toLowerCase(java.util.Locale.ROOT).startsWith("https://");
        return rest + (secure ? ":443" : ":80");
    }

    /** @return "ws", "direct-ip", "direct" or "relay" once a connection is up, null before that */
    public String getConnectPath() {
        return connectPath;
    }

    /**
     * Recognises an {@code IPv4}, {@code IPv4:port}, or a dotted {@code host:port} (a tunnel node
     * or a named LAN machine). Falls back to RustDesk's default direct access port when none is
     * given.
     */
    private static InetSocketAddress parseDirectAddress(String peerId) {
        if (peerId == null) return null;
        String value = peerId.trim();
        String host = value;
        int port = 21118;
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            host = value.substring(0, colon);
            try {
                port = Integer.parseInt(value.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (port <= 0 || port > 65535) return null;
        }
        if (host.isEmpty()) return null;
        if (isIpv4(host)) return new InetSocketAddress(host, port);
        // A named endpoint, e.g. the node a TCP tunnel hands out. Only dotted names reach here, so a
        // mistyped id never turns into a DNS lookup; the socket resolves it on connect and reports
        // an unknown host as an ordinary connection error.
        if (!host.contains(".")) return null;
        return new InetSocketAddress(host, port);
    }

    @Override
    public void run() {
        int attempt = 0;
        while (running.get()) {
            attempt++;
            boolean retryable = connectOnce();
            if (!running.get()) return;
            if (!retryable) return;
            if (everLoggedIn) {
                int delay = RECONNECT_DELAYS_MS[Math.min(backoffIndex, RECONNECT_DELAYS_MS.length - 1)];
                backoffIndex++;
                delay += (int) (System.nanoTime() % 251L);   // 0..250 ms jitter
                status("reconnecting in " + delay + " ms");
                if (!sleepInterruptibly(delay)) return;
                continue;
            }
            if (attempt >= MAX_ATTEMPTS) {
                closed("gave up after " + MAX_ATTEMPTS + " attempts");
                return;
            }
            status("reconnecting (" + attempt + "/" + (MAX_ATTEMPTS - 1) + ")");
            if (!sleepInterruptibly(1500L * attempt)) return;
        }
    }

    /** @return false when stop() interrupted the wait */
    private boolean sleepInterruptibly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** @return true when the connection dropped and retrying makes sense */
    private boolean connectOnce() {
        Socket socket = null;
        try {
            // "10.0.0.5" or "10.0.0.5:21118" means the user wants a direct connection with no
            // rendezvous at all, which is the only way this works on a network without internet.
            String tunnel = normalizeTunnelUrl(peerId);
            if (tunnel != null) {
                status("tunnel " + tunnel);
                tunnelUsername = tunnelUserFromUrl(tunnel);
                Transport raw = new WsTransport(tunnel);
                connectPath = "ws";
                PeerHandshake.Result hs = PeerHandshake.connect(raw, null, null, null);
                transport = hs.transport;
                status("secured=" + hs.security);
                startSender(hs.transport);
                if (hs.pending != null && hs.pending.hasHash()) {
                    handleHash(hs.transport, hs.pending.getHash());
                }
                return loop(hs.transport);
            }

            InetSocketAddress directIp = parseDirectAddress(peerId);
            if (directIp != null) {
                status("direct ip " + directIp);
                socket = new Socket();
                socket.connect(directIp, 5_000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(0);
                socket.setKeepAlive(true);
                Transport raw = new StreamTransport(socket);
                connectPath = "direct-ip";
                // No server exists to vouch for the peer, so neither the deployment key nor a peer
                // signing key is available and the handshake reads the identity without checking it.
                PeerHandshake.Result hs = PeerHandshake.connect(raw, null, null, null);
                transport = hs.transport;
                status("secured=" + hs.security);
                startSender(hs.transport);
                if (hs.pending != null && hs.pending.hasHash()) {
                    // A direct peer often opens with the password challenge, so the handshake
                    // hands it back here instead of dropping it.
                    handleHash(hs.transport, hs.pending.getHash());
                }
                return loop(hs.transport);
            }

            status("resolving peer through rendezvous");
            RendezvousClient rendezvous = new RendezvousClient(
                    rendezvousServer, rendezvousPort, "1.5.1");
            RendezvousClient.PeerAddress addr =
                    rendezvous.resolve(identity.getId(), identity.getSignPublicKey(), peerId,
                            PeerIdentity.decodeRsPublicKey(PeerIdentity.DEFAULT_RS_PUB_KEY),
                            identity.getRegisterUuid());

            Transport raw;
            if (addr.hasDirect()) {
                status("connecting to " + addr.direct);
                try {
                    socket = new Socket();
                    socket.connect(addr.direct, 5_000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(0);
                    socket.setKeepAlive(true);
                    raw = new StreamTransport(socket);
                    connectPath = "direct";
                } catch (IOException e) {
                    // The server often hands out both paths; a refused or timed out direct leg
                    // says nothing about the relay leg, so fall back to it instead of failing.
                    status("direct failed: " + e.getMessage());
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                            // best effort
                        }
                        socket = null;
                    }
                    if (!addr.hasRelay()) {
                        status("no relay fallback after direct failure");
                        return true;
                    }
                    status("relaying via " + addr.relayServer);
                    raw = RelayClient.open(RelayClient.hostOf(addr.relayServer),
                            RelayClient.portOf(addr.relayServer, RelayClient.RELAY_PORT),
                            peerId, addr.uuid);
                    connectPath = "relay";
                }
            } else if (addr.hasRelay()) {
                status("relaying via " + addr.relayServer);
                raw = RelayClient.open(RelayClient.hostOf(addr.relayServer),
                        RelayClient.portOf(addr.relayServer, RelayClient.RELAY_PORT),
                        peerId, addr.uuid);
                connectPath = "relay";
            } else {
                status("peer unreachable: " + addr.failure);
                return true;
            }

            // The rendezvous server hands us the peer identity it signed; its key is the peer's
            // ed25519 signing key, which is what verifies the peer's own SignedId.
            byte[] rsKey = PeerIdentity.decodeRsPublicKey(PeerIdentity.DEFAULT_RS_PUB_KEY);
            byte[] peerSigningKey = null;
            if (addr.peerPk != null && addr.peerPk.length > 0) {
                try {
                    peerSigningKey = PeerIdentity.verify(addr.peerPk, rsKey).publicKey;
                } catch (IllegalArgumentException e) {
                    status("rendezvous identity rejected: " + e.getMessage());
                }
            }

            PeerHandshake.Result handshake =
                    PeerHandshake.connect(raw, peerId, rsKey, peerSigningKey);
            transport = handshake.transport;
            startSender(handshake.transport);
            status("secured=" + handshake.security);

            return loop(handshake.transport);
        } catch (Exception e) {
            status("session error: " + e.getMessage());
            return true;
        } finally {
            loggedIn = false;
            Transport t = transport;
            transport = null;
            if (t != null) t.close();
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** @return true when the drop is retryable, false when the session must end */
    private boolean loop(Transport transport) throws IOException {
        int parseErrors = 0;
        while (running.get()) {
            byte[] frame;
            try {
                frame = transport.receive();
            } catch (IOException e) {
                // stop() closes the transport under a blocked receive(); that is a normal exit.
                if (!running.get()) return false;
                throw e;
            }
            if (frame == null) {
                status("peer closed the connection");
                return true;
            }
            Message message;
            try {
                message = Message.parseFrom(frame);
            } catch (Exception e) {
                parseErrors++;
                if (parseErrors > MAX_PARSE_ERRORS) {
                    closed("protocol error: " + parseErrors + " undecodable frames");
                    return false;
                }
                continue;
            }
            lastInboundNanos = System.nanoTime();
            switch (message.getUnionCase()) {
                case HASH:
                    handleHash(transport, message.getHash());
                    break;
                case LOGIN_RESPONSE: {
                    boolean wasLoggedIn = loggedIn;
                    if (!handleLoginResponse(message.getLoginResponse())) return false;
                    if (!wasLoggedIn) startWatchdog(transport);
                    break;
                }
                case VIDEO_FRAME: {
                    int received = handleVideoFrame(message.getVideoFrame());
                    if (received > 0) {
                        long total = framesReceived + received;
                        framesReceived = total;
                        if (total / 100 > (total - received) / 100) {
                            status("streaming, " + total + " frames");
                        }
                    }
                    break;
                }
                case TEST_DELAY:
                    // A from_client probe is the peer echoing our keepalive; echoing it again
                    // would ping-pong. Host-originated probes are mirrored back as before.
                    if (!message.getTestDelay().getFromClient()) {
                        enqueueControl(Message.newBuilder().setTestDelay(message.getTestDelay()).build());
                    }
                    break;
                case MISC:
                    // Misc.close_reason (field 9) is the peer telling us why it is hanging up.
                    if (message.getMisc().getUnionCase() == Misc.UnionCase.CLOSE_REASON) {
                        closed("peer closed: " + message.getMisc().getCloseReason());
                        return false;
                    }
                    if (message.getMisc().getUnionCase() == Misc.UnionCase.SWITCH_DISPLAY) {
                        applySwitchDisplay(message.getMisc().getSwitchDisplay());
                    }
                    break;
                case CLIPBOARD:
                case MULTI_CLIPBOARDS:
                    handleClipboard(message);
                    break;
                case TERMINAL_RESPONSE:
                    handleTerminalResponse(message.getTerminalResponse());
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    private void handleHash(Transport transport, Hash hash) throws IOException {
        // Two stage, matching validate_password_plain on the host:
        //   h1     = sha256(password || salt)
        //   digest = sha256(h1 || challenge)
        byte[] salt = hash.getSalt().getBytes(StandardCharsets.UTF_8);
        byte[] challenge = hash.getChallenge().getBytes(StandardCharsets.UTF_8);
        byte[] h1 = sha256(concat(password.getBytes(StandardCharsets.UTF_8), salt));
        byte[] digest = sha256(concat(h1, challenge));
        LoginRequest request = LoginRequest.newBuilder()
                // The host compares this against its own id and answers "Offline" on a mismatch
                // (src/server/connection.rs:3014).
                .setUsername(tunnelUsername != null ? tunnelUsername : peerId)
                .setMyId(identity.getId())
                .setMyName("rustdesk-java")
                .setMyPlatform("Android")
                .setVersion("1.5.1")
                .setPassword(ByteString.copyFrom(digest))
                .setSessionId(System.currentTimeMillis() & 0xFFFFFFFFL)
                .setOption(videoSettings.toOptionMessage())
                .build();
        status("login username=" + request.getUsername());
        transport.send(Message.newBuilder().setLoginRequest(request).build().toByteArray());
        status("password challenge answered");
    }

    /** @return false when the login was rejected and retrying cannot help */
    private boolean handleLoginResponse(LoginResponse response) {
        if (response.hasError()) {
            closed("login rejected: " + response.getError());
            return false;
        }
        loggedIn = true;
        everLoggedIn = true;
        backoffIndex = 0;
        if (response.hasPeerInfo()) {
            int width = 0;
            int height = 0;
            if (response.getPeerInfo().getDisplaysCount() > 0) {
                width = response.getPeerInfo().getDisplays(0).getWidth();
                height = response.getPeerInfo().getDisplays(0).getHeight();
            }
            if (width > 0 && height > 0) {
                displayWidth = width;
                displayHeight = height;
            }
            peerPlatform = response.getPeerInfo().getPlatform();
            status("login ok");
            listener.onPeerInfo(peerPlatform, width, height);
        } else {
            status("login ok");
        }
        // A reconnected session starts at the host default; replay the chosen stream scale.
        // The login option already carried the frame rate.
        if (videoSettings.hasCustomScale()) {
            enqueue(videoSettings.toScaleMessage());
        }
        return true;
    }

    /** @return number of encoded frames handed to the decoder */
    private int handleVideoFrame(VideoFrame frame) {
        if (decoder == null) return 0;
        EncodedVideoFrames frames;
        String mime;
        switch (frame.getUnionCase()) {
            case H264S:
                frames = frame.getH264S();
                mime = VideoDecoder.MIME_H264;
                lastVideoMime = 1;
                break;
            case H265S:
                frames = frame.getH265S();
                mime = VideoDecoder.MIME_H265;
                lastVideoMime = 2;
                break;
            case VP8S:
                frames = frame.getVp8S();
                mime = VideoDecoder.MIME_VP8;
                lastVideoMime = 3;
                break;
            case VP9S:
                frames = frame.getVp9S();
                mime = VideoDecoder.MIME_VP9;
                lastVideoMime = 4;
                break;
            case AV1S:
                frames = frame.getAv1S();
                mime = VideoDecoder.MIME_AV1;
                lastVideoMime = 5;
                break;
            default:
                return 0;
        }
        boolean first = true;
        int received = 0;
        for (EncodedVideoFrame f : frames.getFramesList()) {
            if (first) {
                // Hand the decoder the size the host transmits; a 0x0 format makes some vendor
                // decoders refuse to configure.
                applyDecoderSize(mime);
                first = false;
            }
            bytesReceived += f.getData().size();
            decoder.queueFrame(f.getData().toByteArray(), f.getKey(), f.getPts());
            received++;
        }
        return received;
    }

    /**
     * Delivers the peer's clipboard text. {@link ClipboardMessages#readText(Message)} already
     * unwraps multi_clipboards (first Text entry wins), so one handler serves both union cases.
     */
    private void handleClipboard(Message message) {
        String text;
        try {
            text = ClipboardMessages.readText(message);
        } catch (UnsupportedOperationException e) {
            // A large payload the peer zstd-compressed; this client has no zstd, so skip it
            // rather than take the session down on a single clipboard message.
            status("clipboard: compressed payload skipped");
            return;
        }
        if (text == null) return;
        if (listener != null) listener.onClipboardText(text);
    }

    private void handleTerminalResponse(TerminalResponse response) {
        switch (response.getUnionCase()) {
            case DATA: {
                TerminalData data = response.getData();
                if (listener != null) {
                    listener.onTerminalData(data.getTerminalId(), data.getData().toByteArray());
                }
                break;
            }
            case CLOSED: {
                TerminalClosed closed = response.getClosed();
                if (listener != null) {
                    listener.onTerminalClosed(closed.getTerminalId(), closed.getExitCode());
                }
                break;
            }
            case ERROR: {
                // Message and id only: terminal payloads can hold what the user typed.
                TerminalError error = response.getError();
                status("terminal error: " + error.getMessage()
                        + " (terminal_id=" + error.getTerminalId() + ")");
                break;
            }
            case OPENED:
                status("terminal opened id=" + response.getOpened().getTerminalId());
                break;
            default:
                break;
        }
    }

    /** Applies a SwitchDisplay from the host: the captured desktop changed size or display. */
    private void applySwitchDisplay(SwitchDisplay display) {
        if (display == null) return;
        if (display.getWidth() > 0 && display.getHeight() > 0) {
            displayWidth = display.getWidth();
            displayHeight = display.getHeight();
        }
        status("display " + display.getDisplay() + ", " + displayWidth + "x" + displayHeight);
        // The remote desktop changed size: touch mapping, the view and the decoder must follow it.
        if (lastVideoMime != 0) {
            applyDecoderSize(VideoDecoder.mimeFor(lastVideoMime));
        }
        if (listener != null) {
            listener.onPeerInfo(peerPlatform, displayWidth, displayHeight);
        }
    }

    /**
     * Watches the link and probes the peer. Exits as soon as the session stops or a newer
     * connection replaces {@code myTransport}.
     */
    private void startWatchdog(final Transport myTransport) {
        lastInboundNanos = System.nanoTime();
        lastPingNanos = lastInboundNanos;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get() && transport == myTransport) {
                    try {
                        Thread.sleep(KEEPALIVE_TICK_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!running.get() || transport != myTransport) return;
                    long now = System.nanoTime();
                    if (now - lastInboundNanos > WATCHDOG_TIMEOUT_MS * 1_000_000L) {
                        status("connection stalled, reconnecting");
                        myTransport.close();
                        return;
                    }
                    if (now - lastPingNanos > KEEPALIVE_INTERVAL_MS * 1_000_000L) {
                        lastPingNanos = now;
                        enqueueControl(Message.newBuilder()
                                .setTestDelay(TestDelay.newBuilder()
                                        .setFromClient(true)
                                        .setTime(System.currentTimeMillis())
                                        .build())
                                .build());
                    }
                }
            }
        }, "rustdesk-keepalive");
        thread.setDaemon(true);
        thread.start();
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }

    private void closed(String reason) {
        if (listener != null) listener.onClosed(reason);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}









