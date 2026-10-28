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
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.LoginResponse;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.Misc;
import hbb.MessageOuterClass.TerminalClosed;
import hbb.MessageOuterClass.TerminalData;
import hbb.MessageOuterClass.TerminalError;
import hbb.MessageOuterClass.TerminalResponse;
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
        t.start();
    }

    public void stop() {
        running.set(false);
        // Wake the sender out of take() so it can finish; the empty array is the shutdown marker.
        outbox.offer(new byte[0]);
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

    private void startSender() {
        outbox.clear();
        Thread thread = new Thread(this::senderLoop, "rustdesk-sender");
        thread.setDaemon(true);
        senderThread = thread;
        thread.start();
    }

    /** Drains {@link #outbox} onto the transport; exits when the queue is poisoned by stop(). */
    private void senderLoop() {
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
            Transport t = transport;
            if (t == null) {
                return;
            }
            try {
                t.send(payload);
            } catch (IOException e) {
                status("send failed: " + e.getMessage());
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
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && running.get(); attempt++) {
            boolean endedCleanly = connectOnce();
            if (endedCleanly || !running.get()) {
                return;
            }
            if (attempt < MAX_ATTEMPTS) {
                status("reconnecting (" + attempt + "/" + (MAX_ATTEMPTS - 1) + ")");
                try {
                    Thread.sleep(1500L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (running.get()) {
            closed("gave up after " + MAX_ATTEMPTS + " attempts");
        }
    }

    /** @return true when the session ended in a state that retrying cannot improve */
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
                startSender();
                if (hs.pending != null && hs.pending.hasHash()) {
                    handleHash(hs.transport, hs.pending.getHash());
                }
                loop(hs.transport);
                return true;
            }

            InetSocketAddress directIp = parseDirectAddress(peerId);
            if (directIp != null) {
                status("direct ip " + directIp);
                socket = new Socket();
                socket.connect(directIp, 5_000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(0);
                Transport raw = new StreamTransport(socket);
                connectPath = "direct-ip";
                // No server exists to vouch for the peer, so neither the deployment key nor a peer
                // signing key is available and the handshake reads the identity without checking it.
                PeerHandshake.Result hs = PeerHandshake.connect(raw, null, null, null);
                transport = hs.transport;
                status("secured=" + hs.security);
                startSender();
                if (hs.pending != null && hs.pending.hasHash()) {
                    // A direct peer often opens with the password challenge, so the handshake
                    // hands it back here instead of dropping it.
                    handleHash(hs.transport, hs.pending.getHash());
                }
                loop(hs.transport);
                return true;
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
                        closed("direct failed: " + e.getMessage());
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
                closed("peer unreachable: " + addr.failure);
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
            startSender();
            status("secured=" + handshake.security);

            loop(handshake.transport);
            return true;
        } catch (Exception e) {
            status("session error: " + e.getMessage());
            return false;
        } finally {
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

    private void loop(Transport transport) throws IOException {
        int parseErrors = 0;
        while (running.get()) {
            byte[] frame;
            try {
                frame = transport.receive();
            } catch (IOException e) {
                // stop() closes the transport under a blocked receive(); that is a normal exit.
                if (!running.get()) return;
                throw e;
            }
            if (frame == null) {
                closed("peer closed the connection");
                return;
            }
            Message message;
            try {
                message = Message.parseFrom(frame);
            } catch (Exception e) {
                parseErrors++;
                if (parseErrors > MAX_PARSE_ERRORS) {
                    closed("protocol error: " + parseErrors + " undecodable frames");
                    return;
                }
                continue;
            }
            switch (message.getUnionCase()) {
                case HASH:
                    handleHash(transport, message.getHash());
                    break;
                case LOGIN_RESPONSE:
                    handleLoginResponse(message.getLoginResponse());
                    break;
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
                    // Answering keeps the peer's latency estimate alive; echo the timestamp back.
                    transport.send(Message.newBuilder()
                            .setTestDelay(message.getTestDelay())
                            .build().toByteArray());
                    break;
                case MISC:
                    // Misc.close_reason (field 9) is the peer telling us why it is hanging up.
                    if (message.getMisc().getUnionCase() == Misc.UnionCase.CLOSE_REASON) {
                        closed("peer closed: " + message.getMisc().getCloseReason());
                        return;
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
                .build();
        status("login username=" + request.getUsername());
        transport.send(Message.newBuilder().setLoginRequest(request).build().toByteArray());
        status("password challenge answered");
    }

    private void handleLoginResponse(LoginResponse response) {
        if (response.hasError()) {
            closed("login rejected: " + response.getError());
            return;
        }
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
            status("login ok");
            listener.onPeerInfo(response.getPeerInfo().getPlatform(), width, height);
        }
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
                // Hand the decoder the real display size; a 0x0 format makes some vendor
                // decoders refuse to configure.
                decoder.setMime(mime, displayWidth, displayHeight);
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









