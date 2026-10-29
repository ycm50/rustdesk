package com.rustdesk.java.host;

import com.google.protobuf.ByteString;
import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Encrypt;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.net.EncryptCipher;
import com.rustdesk.java.net.SecureTransport;
import com.rustdesk.java.net.Transport;
import com.rustdesk.java.session.ClipboardMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import hbb.MessageOuterClass.ControlKey;
import hbb.MessageOuterClass.DisplayInfo;
import hbb.MessageOuterClass.EncodedVideoFrame;
import hbb.MessageOuterClass.EncodedVideoFrames;
import hbb.MessageOuterClass.Hash;
import hbb.MessageOuterClass.KeyEvent;
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.LoginResponse;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.PeerInfo;
import hbb.MessageOuterClass.PublicKey;
import hbb.MessageOuterClass.SignedId;
import hbb.MessageOuterClass.VideoFrame;
import hbb.Rendezvous.IdPk;

/**
 * The controlled side (responder role) of a RustDesk session.
 *
 * <p>It does exactly three things on one {@link Transport}: prove who it is, answer the key
 * exchange, check the password challenge, and push encoded video frames. Like the Rust host
 * ({@code src/server.rs:identity_handshake}), the host speaks <em>first</em>: it sends
 * {@code Message.signed_id} carrying {@code ed25519(idPk)} where {@code idPk} names this host and
 * carries its ephemeral curve25519 box public key. The viewer verifies that blob with the ed25519
 * key it got from the rendezvous server ({@code PunchHoleResponse.pk}) and only then answers with
 * {@code PublicKey}, which seals the stream key to the box key from the blob.</p>
 *
 * <p>Viewer key events are injected through the {@link HostInputSink} installed with
 * {@link #setInputSink(HostInputSink)}; with no sink installed they are reported and dropped.</p>
 *
 * <p>This class deliberately imports nothing from {@code android.*}; the platform string comes
 * from {@link #PLATFORM}, the display size is supplied by the caller through
 * {@link #setDisplaySize(int, int)} and input goes through an interface. It runs unchanged on a
 * plain JVM, which is how the loopback tests drive it.</p>
 *
 * <p>Not implemented, by design:</p>
 * <ul>
 *   <li>Registering the host with the rendezvous server ({@code RegisterPeer}) and obtaining the
 *       ed25519 key a viewer would learn from {@code PunchHoleResponse.pk}; the signing key is
 *       handed to the session instead.</li>
 *   <li>Mouse and touch events: only key events are dispatched so far.</li>
 *   <li>Release events for control keys: the accessibility API has no key-up channel.</li>
 *   <li>Audio and file transfer: out of scope for this class.</li>
 * </ul>
 *
 * <p>Clipboard text travels both ways once a {@link HostClipboardSink} is installed with
 * {@link #setClipboardSink(HostClipboardSink)}: viewer text is written to the local clipboard and
 * the local clipboard is sampled every {@link #CLIPBOARD_POLL_MS} milliseconds so changes go back
 * to the viewer.</p>
 *
 * <p>Only {@link #sendMessage(Message)} may be called from another thread; everything else is
 * driven by {@link #run()}. The listener is invoked on the session thread.</p>
 */
public final class HostSession implements Runnable {

    /** Receives the session's progress. */
    public interface Listener {
        /** Human readable progress, also useful in a log. */
        void onStatus(String status);

        /** The viewer passed the password challenge; streaming starts next. */
        void onLoginOk(String viewerId);

        /** Called exactly once when the session stops for any reason. */
        void onClosed(String reason);
    }

    /**
     * Supplies already encoded frames, for example from the MediaCodec layer.
     *
     * <p>{@link #nextFrame(Frame)} is called only from the session thread. It must not block for
     * long; returning {@code false} means "no frame right now" and the session simply polls
     * again.</p>
     */
    public interface FrameSource {
        /** Fills {@code out} with the next frame; returns false when none is available. */
        boolean nextFrame(Frame out);
    }

    /** One encoded video frame, in the shape the wire message needs. */
    public static final class Frame {
        /** Encoded H.264 access unit; required. */
        public byte[] data;
        /** True for an IDR/key frame. */
        public boolean key;
        /** Presentation timestamp in milliseconds. */
        public long ptsMs;
    }

    /** Platform string reported in {@code PeerInfo}. Android API must not be used here. */
    public static final String PLATFORM = "Android";

    /** How long the streaming loop sleeps when the frame source has nothing ready. */
    public static final int FRAME_POLL_SLEEP_MS = 10;

    /** How often the local clipboard is sampled for text changes to forward to the viewer. */
    public static final int CLIPBOARD_POLL_MS = 2000;

    /** Protocol error strings the viewer expects, from src/server/connection.rs. */
    private static final String ERROR_WRONG_PASSWORD = "Wrong Password";
    private static final String ERROR_OFFLINE = "Offline";

    /**
     * Key exchange version this host advertises in {@code SignedId}: 1 means "split keys
     * supported", matching {@code tcp::KX_VERSION_LATEST} on the Rust side. A viewer that only
     * speaks 0 answers with 0 and {@link Encrypt#kxVersionFor(int)} downgrades the transcript.
     */
    public static final int HOST_KX_VERSION = Encrypt.KX_VERSION_LATEST;

    private static final int SALT_BYTES = 16;
    private static final int CHALLENGE_BYTES = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final byte[] ZERO_NONCE = new byte[Sodium.NONCE_BYTES];

    // Android key codes as plain ints: importing android.view.KeyEvent would tie this class to
    // the platform. The values are android.view.KeyEvent.KEYCODE_*; InputInjector is the one that
    // decides which of them the accessibility API can actually deliver.
    private static final int KEYCODE_HOME = 3;
    private static final int KEYCODE_DPAD_UP = 19;
    private static final int KEYCODE_DPAD_DOWN = 20;
    private static final int KEYCODE_DPAD_LEFT = 21;
    private static final int KEYCODE_DPAD_RIGHT = 22;
    private static final int KEYCODE_ALT_LEFT = 57;
    private static final int KEYCODE_SHIFT_LEFT = 59;
    private static final int KEYCODE_TAB = 61;
    private static final int KEYCODE_SPACE = 62;
    private static final int KEYCODE_ENTER = 66;
    private static final int KEYCODE_DEL = 67;
    private static final int KEYCODE_PAGE_UP = 92;
    private static final int KEYCODE_PAGE_DOWN = 93;
    private static final int KEYCODE_ESCAPE = 111;
    private static final int KEYCODE_FORWARD_DEL = 112;
    private static final int KEYCODE_CTRL_LEFT = 113;
    private static final int KEYCODE_CAPS_LOCK = 115;
    private static final int KEYCODE_META_LEFT = 117;
    private static final int KEYCODE_MOVE_END = 123;
    private static final int KEYCODE_INSERT = 124;
    private static final int KEYCODE_F1 = 131;
    private static final int KEYCODE_NUMPAD_0 = 144;
    private static final int KEYCODE_NUMPAD_ENTER = 160;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Transport raw;
    private final String hostId;
    private final String password;
    private final byte[] hostPublicKey;
    private final byte[] hostSecretKey;
    private final byte[] hostSigningSeed;
    private final byte[] hostSigningPublicKey;
    private final Listener listener;
    private final FrameSource source;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closedNotified = new AtomicBoolean(false);
    /** Serializes seal+write so two senders cannot swap the cipher counter order. */
    private final Object sendLock = new Object();

    /** Raw until the handshake completes, then the {@link SecureTransport} wrapping it. */
    private volatile Transport transport;
    private volatile Thread worker;
    private volatile boolean loggedIn;
    private volatile boolean stopRequested;
    private volatile int displayWidth;
    private volatile int displayHeight;
    /** Platform injector for viewer key events; null means "report and drop". */
    private volatile HostInputSink inputSink;
    /** Platform clipboard; null means "ignore viewer clipboard and never read the local one". */
    private volatile HostClipboardSink clipboardSink;
    /**
     * Last clipboard text moved in either direction. Both threads that touch it only read or
     * replace the reference, which is atomic on a String field, so a rare lost update at worst
     * re-sends one unchanged value instead of corrupting anything.
     */
    private volatile String lastClipboardText;

    /**
     * Creates a session that signs its {@code SignedId} with a freshly generated ed25519 key.
     *
     * <p>The key is ephemeral, so a remote viewer cannot verify the blob unless
     * {@link #getHostSigningPublicKey()} is published to it out of band. Callers that own the
     * durable host identity should use the overload taking the ed25519 seed.</p>
     */
    public HostSession(Transport transport, String hostId, String password,
                       byte[] hostBoxPublicKey, byte[] hostBoxSecretKey,
                       Listener listener, FrameSource source) {
        this(transport, hostId, password, hostBoxPublicKey, hostBoxSecretKey,
                Ed25519.generateKeyPair()[1], listener, source);
    }

    /**
     * @param hostSigningSeed 32-byte ed25519 seed whose public key a viewer learns from the
     *     rendezvous server ({@code PunchHoleResponse.pk}) and uses to verify our
     *     {@code SignedId}
     */
    public HostSession(Transport transport, String hostId, String password,
                       byte[] hostBoxPublicKey, byte[] hostBoxSecretKey,
                       byte[] hostSigningSeed,
                       Listener listener, FrameSource source) {
        if (transport == null) {
            throw new NullPointerException("transport");
        }
        if (hostId == null) {
            throw new NullPointerException("hostId");
        }
        if (hostBoxPublicKey == null || hostBoxPublicKey.length != Sodium.PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("host public key must be "
                    + Sodium.PUBLIC_KEY_BYTES + " bytes");
        }
        if (hostBoxSecretKey == null || hostBoxSecretKey.length != Sodium.SECRET_KEY_BYTES) {
            throw new IllegalArgumentException("host secret key must be "
                    + Sodium.SECRET_KEY_BYTES + " bytes");
        }
        if (hostSigningSeed == null || hostSigningSeed.length != Ed25519.SEED_BYTES) {
            throw new IllegalArgumentException("host ed25519 seed must be "
                    + Ed25519.SEED_BYTES + " bytes");
        }
        this.raw = transport;
        this.transport = transport;
        this.hostId = hostId;
        this.password = password == null ? "" : password;
        this.hostPublicKey = hostBoxPublicKey.clone();
        this.hostSecretKey = hostBoxSecretKey.clone();
        this.hostSigningSeed = hostSigningSeed.clone();
        this.hostSigningPublicKey = Ed25519.publicKeyOf(this.hostSigningSeed);
        this.listener = listener;
        this.source = source;
    }

    /**
     * The ed25519 public key that verifies this session's {@code SignedId}: exactly what a viewer
     * receives in {@code PunchHoleResponse.pk}. Only needed when the constructor generated the
     * ephemeral key.
     */
    public byte[] getHostSigningPublicKey() {
        return hostSigningPublicKey.clone();
    }

    /** Display size sent in {@code LoginResponse.peer_info.displays[0]}; 0x0 by default. */
    public void setDisplaySize(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("display size must not be negative");
        }
        displayWidth = width;
        displayHeight = height;
    }

    /**
     * Installs the platform injector used for viewer key events. Until one is set every key
     * event is reported through {@link Listener#onStatus(String)} and dropped, which keeps this
     * class usable on a plain JVM.
     */
    public void setInputSink(HostInputSink sink) {
        inputSink = sink;
    }

    /**
     * Installs the platform clipboard. Until one is set viewer clipboard messages are ignored and
     * the local clipboard is never read, which keeps this class usable on a plain JVM.
     */
    public void setClipboardSink(HostClipboardSink sink) {
        clipboardSink = sink;
    }

    /** Starts the session thread. Calling it twice is a no-op. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running.set(true);
        Thread t = new Thread(this, "rustdesk-host-session");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /**
     * Ends the session. Closing the transport is what unblocks a pending {@code receive()};
     * the streaming loop notices the flag or the interrupt within one poll interval.
     */
    public void stop() {
        stopRequested = true;
        running.set(false);
        loggedIn = false;
        closeTransport();
        Thread t = worker;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    /** True between a successful login and the end of the session. */
    public boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * Sends an already built message. Returns false before login or after the session ended.
     * Safe to call from another thread; sends are serialized with the frame stream.
     */
    public boolean sendMessage(Message message) {
        if (message == null) {
            return false;
        }
        Transport t = transport;
        if (t == null || !running.get() || !loggedIn) {
            return false;
        }
        return send(t, message);
    }

    @Override
    public void run() {
        if (!started.get()) {
            // Allow run() to be called directly, without start().
            started.set(true);
            running.set(true);
        }
        if (!running.get()) {
            notifyClosed(stopRequested ? "stopped" : "not started");
            return;
        }

        String reason = "session closed";
        try {
            reason = serve();
        } catch (IOException e) {
            reason = running.get() ? "connection error: " + e.getMessage() : "stopped";
        } catch (RuntimeException e) {
            reason = "host error: " + e;
        } finally {
            running.set(false);
            loggedIn = false;
            closeTransport();
            worker = null;
        }
        notifyClosed(stopRequested ? "stopped" : reason);
    }

    /** @return the reason the session ended, for {@link Listener#onClosed(String)}. */
    private String serve() throws IOException {
        // Host-first, like src/server.rs:identity_handshake: our signed identity goes out before
        // we read anything, because the viewer blocks on it. Sending it later deadlocks both sides.
        status("sending the host's signed id");
        if (!send(raw, signedIdMessage())) {
            return "connection lost while sending the signed id";
        }

        status("waiting for the viewer's public_key");

        Message first;
        try {
            first = receive(raw);
        } catch (IOException e) {
            return "protocol error: first frame is not a message (" + e.getMessage() + ")";
        }
        if (first == null) {
            return "viewer closed before the key exchange";
        }
        if (!first.hasPublicKey()) {
            return "protocol error: expected public_key, got " + first.getUnionCase();
        }

        PublicKey publicKey = first.getPublicKey();
        byte[] viewerPk = publicKey.getAsymmetricValue().toByteArray();
        byte[] sealedKey = publicKey.getSymmetricValue().toByteArray();
        int advertised = publicKey.getKxVersion();
        int picked = Encrypt.kxVersionFor(advertised);

        // Inverse of PeerHandshake.connect: the viewer sealed the session key to our public key
        // with its own secret key (line 100 there opens it here).
        byte[] sessionKey;
        try {
            sessionKey = Sodium.boxOpen(sealedKey, ZERO_NONCE, viewerPk, hostSecretKey);
        } catch (RuntimeException e) {
            return "handshake failed: cannot unseal the session key (" + e.getMessage() + ")";
        }
        if (sessionKey.length != Encrypt.KEY_BYTES) {
            Arrays.fill(sessionKey, (byte) 0);
            return "handshake failed: session key is not " + Encrypt.KEY_BYTES + " bytes";
        }

        // The initiator (viewer) is isInitiator=true; the responder uses false so its send key is
        // the responder subkey. The viewer puts its picked version on the wire, and this
        // responder repeats it as its own advertised version, which matches when both sides
        // support the same latest version (the case the protocol facts were verified with).
        Encrypt encrypt;
        try {
            encrypt = Encrypt.newSplit(sessionKey, false, viewerPk, hostPublicKey,
                    advertised, picked);
        } finally {
            Arrays.fill(sessionKey, (byte) 0);
        }

        Transport secure = new SecureTransport(raw, new EncryptCipher(encrypt));
        transport = secure;
        status("secured, kx_version=" + picked);

        String salt = randomHex(SALT_BYTES);
        String challenge = randomHex(CHALLENGE_BYTES);
        Hash hash = Hash.newBuilder().setSalt(salt).setChallenge(challenge).build();
        if (!send(secure, Message.newBuilder().setHash(hash).build())) {
            return "connection lost while sending the password challenge";
        }
        status("password challenge sent");

        LoginRequest request = awaitLoginRequest(secure);
        if (request == null) {
            return running.get() ? "viewer closed before login" : "stopped";
        }

        String viewerId = request.getMyId();
        // src/server/connection.rs compares the claimed username with the host id and answers
        // "Offline" on a mismatch, which is how a viewer learns it dialed the wrong id.
        if (!hostId.equals(request.getUsername())) {
            sendLoginError(secure, ERROR_OFFLINE);
            return "login rejected: wrong id " + request.getUsername();
        }
        byte[] expected = passwordDigest(password, salt, challenge);
        try {
            if (!MessageDigest.isEqual(expected, request.getPassword().toByteArray())) {
                sendLoginError(secure, ERROR_WRONG_PASSWORD);
                return "login rejected: wrong password";
            }
        } finally {
            Arrays.fill(expected, (byte) 0);
        }

        LoginResponse response = LoginResponse.newBuilder()
                .setPeerInfo(PeerInfo.newBuilder()
                        .setPlatform(PLATFORM)
                        .addDisplays(DisplayInfo.newBuilder()
                                .setWidth(displayWidth)
                                .setHeight(displayHeight)))
                .build();
        if (!send(secure, Message.newBuilder().setLoginResponse(response).build())) {
            return "connection lost while sending the login response";
        }
        loggedIn = true;
        status("login ok, viewer=" + viewerId);
        notifyLoginOk(viewerId);

        // From here the protocol is bidirectional: the viewer sends key events while frames go
        // out, and receive() blocks, so inbound messages need a reader of their own.
        startReceiver(secure);
        return stream(secure, viewerId);
    }

    /**
     * Waits for {@code LoginRequest}. Any other message before login (latency probes included)
     * is ignored, except {@code TestDelay}, which is echoed like the Rust host does.
     */
    private LoginRequest awaitLoginRequest(Transport secure) throws IOException {
        while (running.get()) {
            Message message;
            try {
                message = receive(secure);
            } catch (IOException e) {
                if (!running.get()) {
                    return null;
                }
                throw e;
            }
            if (message == null) {
                return null;
            }
            switch (message.getUnionCase()) {
                case LOGIN_REQUEST:
                    return message.getLoginRequest();
                case TEST_DELAY:
                    send(secure, Message.newBuilder()
                            .setTestDelay(message.getTestDelay())
                            .build());
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    /** Pushes frames until the session is stopped or the transport fails. */
    private String stream(Transport secure, String viewerId) throws IOException {
        status("streaming to " + viewerId);
        Frame frame = new Frame();
        int sent = 0;
        while (running.get()) {
            boolean hasFrame;
            try {
                hasFrame = source != null && source.nextFrame(frame);
            } catch (RuntimeException e) {
                return "frame source failed: " + e;
            }
            if (!hasFrame || frame.data == null || frame.data.length == 0) {
                sleepQuietly();
                continue;
            }
            Message video = videoMessage(frame.data, frame.key, frame.ptsMs);
            if (!send(secure, video)) {
                return running.get() ? "connection lost while streaming" : "stopped";
            }
            sent++;
            if (sent == 1) {
                status("sent the first video frame");
            }
        }
        return "stopped";
    }

    // ------------------------------------------------------------------ input

    /**
     * Starts the post-login reader. It is the session's only {@code receive()} caller from here
     * on: the worker thread stays in {@link #stream(Transport, String)} so frames keep flowing,
     * and closing the transport is what ends the reader.
     */
    private void startReceiver(final Transport secure) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop(secure);
            }
        }, "rustdesk-host-input");
        reader.setDaemon(true);
        reader.start();

        // receive() blocks with no timeout, so the reader itself cannot tick the clipboard. The
        // poller is a second daemon thread with the same lifetime instead.
        Thread clipboard = new Thread(new Runnable() {
            @Override
            public void run() {
                clipboardLoop(secure);
            }
        }, "rustdesk-host-clipboard");
        clipboard.setDaemon(true);
        clipboard.start();
    }

    /** Dispatches inbound messages until the session stops or the viewer goes away. */
    private void receiveLoop(Transport secure) {
        while (running.get()) {
            Message message;
            try {
                message = receive(secure);
            } catch (IOException e) {
                if (running.get()) {
                    status("input reader stopped: " + e.getMessage());
                }
                return;
            }
            if (message == null) {
                return;
            }
            switch (message.getUnionCase()) {
                case KEY_EVENT:
                    handleKeyEvent(message.getKeyEvent());
                    break;
                case TEST_DELAY:
                    // The viewer probes the link while the screen is static; echoing keeps its
                    // watchdog from treating a quiet but healthy session as a dead one.
                    send(secure, Message.newBuilder()
                            .setTestDelay(message.getTestDelay())
                            .build());
                    break;
                case CLIPBOARD:
                case MULTI_CLIPBOARDS:
                    handleClipboard(message);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Injects one viewer key event. Accessibility is a narrow channel, so the branches are
     * ordered by how reliably they land: text first, then control keys. Every branch that cannot
     * be carried out is reported instead of disappearing silently.
     *
     * <p>{@code unicode} carries a code point while {@code chr} carries a positional key code (a
     * scancode on Windows), so {@code chr} is only used when it happens to be printable.</p>
     */
    private void handleKeyEvent(KeyEvent event) {
        if (event == null) {
            return;
        }
        String seq = event.getSeq(); // "" unless the seq member is set
        if (!seq.isEmpty()) {
            injectText("seq", seq);
            return;
        }
        switch (event.getUnionCase()) {
            case UNICODE:
                injectCodePoint("unicode", event.getUnicode());
                break;
            case CHR:
                injectCodePoint("chr", event.getChr());
                break;
            case CONTROL_KEY:
                injectControlKey(event.getControlKey(), event.getDown() || event.getPress());
                break;
            case WIN2WIN_HOTKEY:
                // A Windows virtual key plus a code point; the accessibility API cannot turn
                // that into a shortcut.
                drop("win2win_hotkey", "no accessibility equivalent");
                break;
            default:
                drop("key_event", "no injectable payload");
                break;
        }
    }

    private void injectText(String field, String text) {
        HostInputSink sink = inputSink;
        if (sink == null) {
            drop(field, "no input sink installed");
            return;
        }
        // Never log the text itself: it may be a password.
        if (!sink.text(text)) {
            drop(field, "the input sink could not type " + text.length() + " character(s)");
        }
    }

    private void injectCodePoint(String field, int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || Character.isISOControl(codePoint)) {
            drop(field, "code " + codePoint + " is not a printable character");
            return;
        }
        injectText(field, new String(Character.toChars(codePoint)));
    }

    private void injectControlKey(ControlKey key, boolean press) {
        int keyCode = androidKeyCode(key);
        if (keyCode < 0) {
            drop("control_key", key.name() + " has no Android key code here");
            return;
        }
        // InputInjector.key() only takes presses: several of its actions are one-shot global
        // actions and the accessibility API has no key-up channel, so a release does nothing and
        // is ignored on purpose.
        if (!press) {
            return;
        }
        HostInputSink sink = inputSink;
        if (sink == null) {
            drop("control_key", "no input sink installed");
            return;
        }
        if (!sink.key(keyCode, true)) {
            drop("control_key", key.name() + " (Android key " + keyCode + ") is not injectable");
        }
    }

    /** Reports a key event that could not be injected; this class has no android.util.Log. */
    private void drop(String kind, String reason) {
        status("input dropped: " + kind + ": " + reason);
    }

    /** Maps a protocol control key to the Android key code of the same physical key, or -1. */
    private static int androidKeyCode(ControlKey key) {
        if (key == null) {
            return -1;
        }
        switch (key) {
            case Return:
                return KEYCODE_ENTER;
            case NumpadEnter:
                return KEYCODE_NUMPAD_ENTER;
            case Backspace:
                return KEYCODE_DEL;
            case Delete:
                return KEYCODE_FORWARD_DEL;
            case Tab:
                return KEYCODE_TAB;
            case Escape:
                return KEYCODE_ESCAPE;
            case Space:
                return KEYCODE_SPACE;
            // The accessibility sink turns this into GLOBAL_ACTION_HOME; an ordinary app cannot
            // move a caret to the start of the line.
            case Home:
                return KEYCODE_HOME;
            case End:
                return KEYCODE_MOVE_END;
            case PageUp:
                return KEYCODE_PAGE_UP;
            case PageDown:
                return KEYCODE_PAGE_DOWN;
            case Insert:
                return KEYCODE_INSERT;
            case UpArrow:
                return KEYCODE_DPAD_UP;
            case DownArrow:
                return KEYCODE_DPAD_DOWN;
            case LeftArrow:
                return KEYCODE_DPAD_LEFT;
            case RightArrow:
                return KEYCODE_DPAD_RIGHT;
            // android.view.KeyEvent.KEYCODE_F1..KEYCODE_F12 are contiguous (131..142).
            case F1:
                return KEYCODE_F1;
            case F2:
                return KEYCODE_F1 + 1;
            case F3:
                return KEYCODE_F1 + 2;
            case F4:
                return KEYCODE_F1 + 3;
            case F5:
                return KEYCODE_F1 + 4;
            case F6:
                return KEYCODE_F1 + 5;
            case F7:
                return KEYCODE_F1 + 6;
            case F8:
                return KEYCODE_F1 + 7;
            case F9:
                return KEYCODE_F1 + 8;
            case F10:
                return KEYCODE_F1 + 9;
            case F11:
                return KEYCODE_F1 + 10;
            case F12:
                return KEYCODE_F1 + 11;
            case Shift:
                return KEYCODE_SHIFT_LEFT;
            case Control:
                return KEYCODE_CTRL_LEFT;
            case Alt:
            case Option:
                return KEYCODE_ALT_LEFT;
            case Meta:
            case RWin:
                return KEYCODE_META_LEFT;
            case CapsLock:
                return KEYCODE_CAPS_LOCK;
            default:
                int number = key.getNumber();
                if (number >= ControlKey.Numpad0.getNumber()
                        && number <= ControlKey.Numpad9.getNumber()) {
                    // KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9 are contiguous as well.
                    return KEYCODE_NUMPAD_0 + (number - ControlKey.Numpad0.getNumber());
                }
                return -1;
        }
    }

    // --------------------------------------------------------------- clipboard

    /**
     * Applies one clipboard message from the viewer to the local clipboard.
     *
     * <p>{@link ClipboardMessages#readText(Message)} returns {@code null} for non-text clipboards
     * (an HTML payload, for example), so those are ignored. It throws for a text payload the peer
     * zstd-compressed, which this client cannot decode: that is reported and skipped instead of
     * killing the reader thread.</p>
     */
    private void handleClipboard(Message message) {
        HostClipboardSink sink = clipboardSink;
        if (sink == null) {
            return;
        }
        String text;
        try {
            text = ClipboardMessages.readText(message);
        } catch (UnsupportedOperationException e) {
            status("clipboard: compressed payload skipped");
            return;
        }
        if (text == null) {
            return;
        }
        // Remember what the viewer just set, otherwise the next poll would bounce it straight back.
        lastClipboardText = text;
        try {
            sink.setText(text);
        } catch (RuntimeException e) {
            status("clipboard: cannot write the local clipboard (" + e + ")");
        }
    }

    /** Polls the local clipboard for changes and forwards each new value to the viewer. */
    private void clipboardLoop(Transport secure) {
        while (running.get()) {
            try {
                Thread.sleep(CLIPBOARD_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running.get()) {
                return;
            }
            pushClipboard(secure);
        }
    }

    private void pushClipboard(Transport secure) {
        HostClipboardSink sink = clipboardSink;
        if (sink == null) {
            return;
        }
        String current;
        try {
            current = sink.getText();
        } catch (RuntimeException e) {
            status("clipboard: cannot read the local clipboard (" + e + ")");
            return;
        }
        if (current == null || current.equals(lastClipboardText)) {
            return;
        }
        lastClipboardText = current;
        // Same send path as frames: Encrypt is synchronized and send() holds sendLock, so this
        // cannot interleave with the streaming thread or a sendMessage() caller.
        if (!send(secure, ClipboardMessages.text(current))) {
            status("clipboard: cannot send the local clipboard");
        }
    }

    /**
     * Builds {@code Message.signed_id}: ed25519 signature(64) over the serialized {@link IdPk}
     * whose {@code pk} is our curve25519 box public key and whose {@code kx_version} is
     * {@link #HOST_KX_VERSION}. Mirrors {@code src/server.rs:identity_handshake}; the viewer
     * verifies it with the ed25519 key it got from the rendezvous server.
     */
    private Message signedIdMessage() {
        IdPk idPk = IdPk.newBuilder()
                .setId(hostId)
                .setPk(ByteString.copyFrom(hostPublicKey))
                .setKxVersion(HOST_KX_VERSION)
                .build();
        byte[] signed = Ed25519.signed(idPk.toByteArray(), hostSigningSeed);
        return Message.newBuilder()
                .setSignedId(SignedId.newBuilder().setId(ByteString.copyFrom(signed)))
                .build();
    }

    private static Message videoMessage(byte[] data, boolean key, long ptsMs) {
        EncodedVideoFrame encoded = EncodedVideoFrame.newBuilder()
                .setData(ByteString.copyFrom(data))
                .setKey(key)
                .setPts(ptsMs)
                .build();
        EncodedVideoFrames frames = EncodedVideoFrames.newBuilder()
                .addFrames(encoded)
                .build();
        VideoFrame video = VideoFrame.newBuilder()
                .setH264S(frames)
                .build();
        return Message.newBuilder().setVideoFrame(video).build();
    }

    private void sendLoginError(Transport secure, String error) {
        LoginResponse response = LoginResponse.newBuilder().setError(error).build();
        send(secure, Message.newBuilder().setLoginResponse(response).build());
        status("login rejected: " + error);
    }

    private boolean send(Transport t, Message message) {
        synchronized (sendLock) {
            try {
                t.send(message.toByteArray());
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private static Message receive(Transport t) throws IOException {
        byte[] frame = t.receive();
        if (frame == null) {
            return null;
        }
        return Message.parseFrom(frame);
    }

    private void closeTransport() {
        Transport t = transport;
        transport = null;
        if (t != null) {
            t.close();
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(FRAME_POLL_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void status(String text) {
        if (listener != null) {
            listener.onStatus(text);
        }
    }

    private void notifyLoginOk(String viewerId) {
        if (listener != null) {
            listener.onLoginOk(viewerId == null ? "" : viewerId);
        }
    }

    private void notifyClosed(String reason) {
        if (listener != null && closedNotified.compareAndSet(false, true)) {
            listener.onClosed(reason);
        }
    }

    /**
     * Host side of the login challenge, the exact inverse of {@code Session.handleHash}:
     * {@code sha256(sha256(password || salt) || challenge)}, with salt and challenge taken as the
     * UTF-8 bytes of the strings that were sent on the wire.
     */
    private static byte[] passwordDigest(String password, String salt, String challenge) {
        byte[] first = sha256(concat(password.getBytes(StandardCharsets.UTF_8),
                salt.getBytes(StandardCharsets.UTF_8)));
        return sha256(concat(first, challenge.getBytes(StandardCharsets.UTF_8)));
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        char[] out = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            int value = random[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
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
