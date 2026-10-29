package com.rustdesk.java.host;

import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.net.StreamTransport;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The controlled end as a whole: a TCP listener a viewer can dial directly, plus the rendezvous
 * registration that makes this host findable.
 *
 * <p>This is the piece the app was missing. {@link HostSession} can serve one viewer once
 * something hands it a connected {@code Transport}, and {@link HostRegistration} can register the
 * id with hbbs and answer {@code PunchHole}, but nothing accepted an inbound connection: after a
 * punch the controller dials the host's advertised address and there was no port listening on it.
 * {@code HostAgent} owns that port:</p>
 *
 * <ol>
 *   <li>{@link #start()} binds {@code 0.0.0.0:0}, records the port the OS picked, and starts an
 *       accept thread;
 *   <li>every accepted socket becomes a {@link HostSession} on that socket, with the durable host
 *       identity and the password given to this agent;
 *   <li>it also starts a {@link HostRegistration} for the same id and ed25519 key, so hbbs learns
 *       the id (and keeps it alive) while the listener waits.
 * </ol>
 *
 * <p>Protocol facts this design follows (see {@code HostRegistration} for the registration
 * details):</p>
 *
 * <ul>
 *   <li>the host registers with {@code RegisterPk} on a persistent TCP stream, not
 *       {@code RegisterPeer};
 *   <li>hbbs pushes {@code PunchHole} down that same stream and expects {@code PunchHoleSent}
 *       back; hbbs then hands the controller the <b>observed</b> source address of the stream that
 *       carried {@code PunchHoleSent} together with the registered {@code pk}. The address is
 *       therefore not something the host states - it is where the host's punch answer came from,
 *       which is why the listening port has to exist before the punch is answered;
 *   <li>the viewer then dials that address and reads the host's {@code Message.signed_id} first,
 *       which is exactly what {@link HostSession} sends on {@code start()}.
 * </ul>
 *
 * <p>Only JDK APIs are used and nothing from {@code android.*} is imported, so the whole class
 * runs on a plain JVM; that is how the loopback interop test drives it (a real
 * {@code session.PeerHandshake} as the viewer, no android.jar on the classpath).</p>
 *
 * <p>The capture pipeline is wired in through {@link #onCapturedFrame(byte[], int, int, long)}:
 * a logged-in session polls {@link #frameSource}, which forwards to the running capture source
 * (EGL uploader plus H.264 encoder). That source is Android-only, so this class reaches it by
 * name and is not compiled against it; see {@link FrameLink}.</p>
 *
 * <p>Not implemented yet, deliberately:</p>
 *
 * <ul>
 *   <li>binding the punch answer to this listening port. {@link HostRegistration} answers
 *       {@code PunchHole} on the persistent stream, so hbbs observes that stream's ephemeral port
 *       rather than {@link #getListenPort()}. That needs a new hook in
 *       {@code HostRegistration}; it cannot be added from here (see the class report).
 * </ul>
 *
 * <p>Thread safety: {@link #start()} and {@link #stop()} are idempotent, may be called from any
 * thread and from each other's callbacks. The {@link Listener} is invoked on the accept thread or
 * on a session thread, never on the caller's.</p>
 */
public final class HostAgent {

    /** Receives the agent's progress and the viewer lifecycle. */
    public interface Listener {        /** Human readable progress, also useful in a log. */
        void onStatus(String status);

        /** A viewer passed the password challenge; streaming would start next. */
        void onViewerConnected(String viewerId);

        /** A viewer's session ended for any reason, including a failed handshake or login. */
        void onViewerClosed(String reason);
    }

    /** Name of the accept thread, so a host can find and assert on it. */
    public static final String ACCEPT_THREAD_NAME = "rustdesk-host-accept";

    /** Listen backlog. One viewer per connection; a handful is plenty. */
    private static final int BACKLOG = 50;

    /** How long {@link #stop()} waits for the accept thread to notice the closed listener. */
    private static final long ACCEPT_JOIN_MS = 2000L;

    /**
     * Bits of the obfuscation term that {@link #encode(InetSocketAddress)} keeps clear. See
     * {@link #encode(InetSocketAddress, long)} for why.
     */
    private static final long TM_LOW_BITS = 0x7FL;

    /** Name of the capture source, resolved at start; see {@link FrameLink}. */
    private static final String FRAME_SOURCE_CLASS = "com.rustdesk.java.host.HostFrameSource";

    /** Encoder frame rate hint; the capture service also mirrors the display at 30 fps. */
    private static final int FRAME_RATE = 30;

    /** Encoder bit rate floor and ceiling in bits per second; see {@link #bitRateFor}. */
    private static final int MIN_BIT_RATE = 1_000_000;
    private static final int MAX_BIT_RATE = 8_000_000;

    private final String hostId;
    private final String password;
    private final String rendezvousServer;
    private final int rendezvousPort;
    private final byte[] hostBoxPublicKey;
    private final byte[] hostBoxSecretKey;
    private final byte[] hostSigningSeed;
    private final Listener listener;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final List<ActiveSession> sessions = new CopyOnWriteArrayList<ActiveSession>();

    /** Guards the creation and the teardown of {@link #frameLink}. */
    private final Object frameLock = new Object();

    /**
     * What a session polls. The reference never changes, so a session keeps following the capture
     * source across a display size change instead of polling the stopped one. Answers false while
     * no source is running, which the session reads as "no frame right now".
     */
    private final HostSession.FrameSource frameSource = new HostSession.FrameSource() {
        @Override
        public boolean nextFrame(HostSession.Frame out) {
            FrameLink link = frameLink;
            return link != null && link.source.nextFrame(out);
        }
    };

    /**
     * The running capture source, or null while none runs: before {@link #start()}, after
     * {@link #stop()}, and on a JVM, where the capture classes do not exist. Guarded by
     * {@link #frameLock}, read without it on the session and capture threads.
     */
    private volatile FrameLink frameLink;

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile HostRegistration registration;
    private volatile int displayWidth;
    private volatile int displayHeight;
    private volatile HostInputSink inputSink;

    /**
     * Injects the viewer's keyboard messages into this device. The Android caller passes the
     * accessibility backed sink; leaving it null just means key events are logged and dropped.
     */
    public void setInputSink(HostInputSink sink) {
        this.inputSink = sink;
    }

    /**
     * @param hostId            RustDesk id this host registers under and answers to in
     *                          {@code LoginRequest.username}
     * @param password          plain password a viewer has to know; the session does the hashing
     * @param rendezvousServer  hbbs host name or address; null or empty disables registration
     *                          (listener only, which is what the loopback test uses)
     * @param rendezvousPort    hbbs TCP port, usually 21116
     * @param hostBoxPublicKey  32-byte curve25519 key the viewer seals the stream key to
     * @param hostBoxSecretKey  32-byte curve25519 secret key matching it
     * @param hostSigningSeed   32-byte ed25519 seed; its public key is what hbbs hands the viewer
     *                          in {@code PunchHoleResponse.pk}, so it must be the same key the
     *                          registration publishes and the sessions sign {@code SignedId} with
     * @param listener          progress and viewer callbacks, may be null
     */
    public HostAgent(String hostId, String password, String rendezvousServer, int rendezvousPort,
                     byte[] hostBoxPublicKey, byte[] hostBoxSecretKey, byte[] hostSigningSeed,
                     Listener listener) {
        if (hostId == null || hostId.isEmpty()) {
            throw new IllegalArgumentException("host id must not be empty");
        }
        if (hostBoxPublicKey == null || hostBoxPublicKey.length != Sodium.PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("host box public key must be "
                    + Sodium.PUBLIC_KEY_BYTES + " bytes");
        }
        if (hostBoxSecretKey == null || hostBoxSecretKey.length != Sodium.SECRET_KEY_BYTES) {
            throw new IllegalArgumentException("host box secret key must be "
                    + Sodium.SECRET_KEY_BYTES + " bytes");
        }
        if (hostSigningSeed == null || hostSigningSeed.length != Ed25519.SEED_BYTES) {
            throw new IllegalArgumentException("host ed25519 seed must be "
                    + Ed25519.SEED_BYTES + " bytes");
        }
        this.hostId = hostId;
        this.password = password == null ? "" : password;
        this.rendezvousServer = rendezvousServer;
        this.rendezvousPort = rendezvousPort;
        this.hostBoxPublicKey = hostBoxPublicKey.clone();
        this.hostBoxSecretKey = hostBoxSecretKey.clone();
        this.hostSigningSeed = hostSigningSeed.clone();
        this.listener = listener;
    }

    /**
     * Binds the listening port, starts the accept thread and starts registration. Calling it twice
     * is a no-op; calling it after {@link #stop()} starts a fresh listener on a new random port.
     *
     * <p>A bind failure is reported through {@link Listener#onStatus(String)} and leaves the agent
     * not running, with {@link #getListenPort()} at -1, so the caller can retry.</p>
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            status("start() ignored: already started");
            return;
        }
        stopping.set(false);
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            // The punch answer eventually has to be sent from a socket bound to this very port
            // (that is how hbbs learns the port), and accepting again after a restart needs it
            // too; set it before bind, where the option actually takes effect.
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0), BACKLOG);
        } catch (IOException e) {
            closeQuietly(socket);
            serverSocket = null;
            started.set(false);
            stopping.set(true);
            status("listen failed: " + e);
            return;
        }
        serverSocket = socket;
        status("listening on 0.0.0.0:" + socket.getLocalPort() + " for host " + hostId);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, ACCEPT_THREAD_NAME);
        t.setDaemon(true);
        acceptThread = t;
        t.start();

        startFrameSource();
        startRegistration();
    }

    /**
     * Closes the listener, stops registration and stops every live viewer session. Idempotent;
     * safe to call from a listener callback or before {@link #start()}.
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        stopping.set(true);
        status("stop() requested");

        ServerSocket socket = serverSocket;
        serverSocket = null;
        // Closing the ServerSocket is what unblocks accept() on every platform.
        closeQuietly(socket);
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null && t != Thread.currentThread()) {
            joinQuietly(t, ACCEPT_JOIN_MS);
        }

        stopRegistration();

        for (ActiveSession active : sessions) {
            HostSession session = active.session;
            if (session != null) {
                session.stop();
            }
        }
        sessions.clear();

        // After the sessions, so no session sees its encoder disappear mid-poll. Idempotent: with
        // no source running this only takes the lock.
        stopFrameSource();
        status("stopped");
    }

    /** @return the port the OS actually bound, or -1 when not listening */
    public int getListenPort() {
        ServerSocket socket = serverSocket;
        if (socket == null || socket.isClosed()) {
            return -1;
        }
        return socket.getLocalPort();
    }

    /** True between a successful {@link #start()} and {@link #stop()}. */
    public boolean isRunning() {
        ServerSocket socket = serverSocket;
        return socket != null && !socket.isClosed() && started.get();
    }

    /** True while hbbs has acknowledged our registration within its timeout window. */
    public boolean isRegistered() {
        HostRegistration reg = registration;
        return reg != null && reg.isRegistered();
    }

    /** Number of viewer sessions that have not ended yet. */
    public int getViewerCount() {
        return sessions.size();
    }

    /**
     * Display size announced in {@code LoginResponse.peer_info.displays}, applied to sessions
     * started from now on. Defaults to 0x0 until the capture layer knows the real size.
     *
     * <p>A running agent also rebuilds its capture source, because the encoder is configured for
     * one size and cannot be resized in place. The rebuilt source is what existing sessions poll,
     * so they get the new size without reconnecting.</p>
     */
    public void setDisplaySize(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("display size must not be negative");
        }
        if (width == displayWidth && height == displayHeight) {
            return;
        }
        displayWidth = width;
        displayHeight = height;
        if (isRunning()) {
            restartFrameSource();
        }
    }

    /**
     * Feeds one captured screen frame to this host's encoder. This is the entire video contract
     * the Android layer needs: it hands over RGBA pixels and never sees the capture source.
     *
     * <p>Called on the capture thread. Frames that arrive while no source is running (not started
     * yet, stopped, or a size change in flight) are dropped.</p>
     *
     * @param rgba tightly packed, top row first RGBA_8888 pixels
     * @param ptsMs capture presentation timestamp in milliseconds
     */
    public void onCapturedFrame(byte[] rgba, int width, int height, long ptsMs) {
        FrameLink link = frameLink;
        if (link == null) {
            return;
        }
        link.feed(rgba, width, height, ptsMs);
    }

    // -------------------------------------------------------------------- capture

    /** Starts the capture source for the current display size; no-op when one already runs. */
    private void startFrameSource() {
        synchronized (frameLock) {
            if (frameLink == null) {
                openFrameSourceLocked();
            }
        }
    }

    /** Stops the capture source, if any. Idempotent. */
    private void stopFrameSource() {
        synchronized (frameLock) {
            FrameLink link = frameLink;
            frameLink = null;
            if (link != null) {
                link.stop();
            }
        }
    }

    /** Swaps in a source for the new display size so existing sessions follow it. */
    private void restartFrameSource() {
        synchronized (frameLock) {
            FrameLink previous = frameLink;
            frameLink = null;
            if (previous != null) {
                previous.stop();
            }
            openFrameSourceLocked();
        }
    }

    /**
     * Creates and starts a source for the current display size. Caller holds {@link #frameLock}.
     */
    private void openFrameSourceLocked() {
        int width = displayWidth;
        int height = displayHeight;
        if (width <= 0 || height <= 0) {
            status("frame source: waiting for a display size");
            return;
        }
        FrameLink link = openFrameLink(width, height);
        if (link == null) {
            status("frame source: capture pipeline unavailable, a viewer will get no video");
            return;
        }
        try {
            link.start();
        } catch (RuntimeException e) {
            // A codec that refuses to start must not take the listener, the registration or the
            // sessions with it; the reason is what the caller sees.
            status("frame source: cannot start the encoder: " + e);
            link.stop();
            return;
        }
        frameLink = link;
        status("frame source: " + width + "x" + height + ", " + FRAME_RATE + " fps, "
                + bitRateFor(width, height) + " bit/s");
    }

    /**
     * Allocates the capture source for one display size, or null when it is not available: on a
     * plain JVM the class does not exist, on a device the encoder may refuse the size.
     */
    private static FrameLink openFrameLink(int width, int height) {
        try {
            Class<?> type = Class.forName(FRAME_SOURCE_CLASS);
            Object source = type.getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(width, height, FRAME_RATE, bitRateFor(width, height));
            return new FrameLink(source,
                    type.getMethod("start"),
                    type.getMethod("stop"),
                    type.getMethod("onCapturedFrame", byte[].class, int.class, int.class,
                            long.class));
        } catch (ReflectiveOperationException e) {
            return null;
        } catch (LinkageError e) {
            // The class is there but a dependency of it is not, for example MediaCodec on a JVM.
            return null;
        }
    }

    /**
     * Target bit rate for one display size: an eighth of a bit per pixel per frame, which is
     * roughly what H.264 needs for screen content, clamped so a small window still gets a usable
     * stream and a large panel cannot ask for more than the link carries.
     */
    private static int bitRateFor(int width, int height) {
        long bits = (long) width * height * FRAME_RATE / 8L;
        if (bits < MIN_BIT_RATE) {
            return MIN_BIT_RATE;
        }
        return bits > MAX_BIT_RATE ? MAX_BIT_RATE : (int) bits;
    }

    /**
     * One capture source, reached without a compile-time reference to it.
     *
     * <p>{@code HostFrameSource} is built on MediaCodec, EGL and {@code android.util.Log}, so
     * naming it here would make this class fail to compile without android.jar and fail to load
     * on the plain JVM the self-test runs on. It is looked up by name once per start instead.
     * Frame polling still goes through {@link HostSession.FrameSource}; only the three methods
     * that interface does not declare are called reflectively.</p>
     */
    private static final class FrameLink {
        final HostSession.FrameSource source;
        private final Method startMethod;
        private final Method stopMethod;
        private final Method feedMethod;

        FrameLink(Object source, Method startMethod, Method stopMethod, Method feedMethod) {
            this.source = (HostSession.FrameSource) source;
            this.startMethod = startMethod;
            this.stopMethod = stopMethod;
            this.feedMethod = feedMethod;
        }

        void start() {
            try {
                startMethod.invoke(source);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("capture source start is not accessible", e);
            } catch (InvocationTargetException e) {
                throw asRuntime(e);
            }
        }

        void stop() {
            try {
                stopMethod.invoke(source);
            } catch (IllegalAccessException e) {
                // Public method on a public class: cannot happen.
            } catch (InvocationTargetException e) {
                // The source logs its own teardown failures and stop() never throws.
            }
        }

        void feed(byte[] rgba, int width, int height, long ptsMs) {
            try {
                feedMethod.invoke(source, rgba, width, height, ptsMs);
            } catch (IllegalAccessException e) {
                // Public method on a public class: cannot happen.
            } catch (InvocationTargetException e) {
                throw asRuntime(e);
            }
        }

        private static RuntimeException asRuntime(InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            return new IllegalStateException(cause);
        }
    }

    // ------------------------------------------------------------------ listener

    private void acceptLoop() {
        ServerSocket server = serverSocket;
        while (!stopping.get() && server != null && !server.isClosed()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!stopping.get()) {
                    status("accept failed: " + e);
                }
                break;
            }
            handleConnection(socket);
        }
        if (!stopping.get()) {
            status("accept loop ended unexpectedly");
        } else {
            status("accept loop finished");
        }
    }

    private void handleConnection(Socket socket) {
        String peer = describe(socket.getRemoteSocketAddress());
        try {
            socket.setTcpNoDelay(true);
            // A viewer that logged in may stay silent for a long time between frames.
            socket.setSoTimeout(0);
        } catch (IOException ignored) {
            // keep going: the defaults still work
        }

        ActiveSession active = new ActiveSession(peer);
        HostSession session;
        try {
            session = new HostSession(new StreamTransport(socket), hostId, password,
                    hostBoxPublicKey, hostBoxSecretKey, hostSigningSeed,
                    new ViewerListener(active), frameSource);
        } catch (Throwable t) {
            closeQuietly(socket);
            status("rejected a connection from " + peer + ": " + t);
            return;
        }
        session.setDisplaySize(displayWidth, displayHeight);
        // Kept as an interface so this class stays free of android.* and JVM-testable; the caller
        // hands in the accessibility backed implementation.
        session.setInputSink(inputSink);
        active.session = session;
        sessions.add(active);

        // stop() may have run between accept() and here; do not leak a session past it.
        if (stopping.get()) {
            sessions.remove(active);
            session.stop();
            return;
        }
        status("viewer connected from " + peer + " (" + sessions.size() + " session(s))");
        session.start();
    }

    /** Per-connection bookkeeping shared by the agent and the session's listener. */
    private static final class ActiveSession {
        final String peer;
        volatile HostSession session;
        volatile String viewerId;

        ActiveSession(String peer) {
            this.peer = peer;
        }
    }

    /**
     * Forwards one session's callbacks. {@code onClosed} fires exactly once per session, also for
     * a connection that never got past the handshake, so the agent's counter and the listener both
     * see every connection.
     */
    private final class ViewerListener implements HostSession.Listener {
        private final ActiveSession active;

        ViewerListener(ActiveSession active) {
            this.active = active;
        }

        @Override
        public void onStatus(String status) {
            HostAgent.this.status("[viewer " + active.peer + "] " + status);
        }

        @Override
        public void onLoginOk(String viewerId) {
            active.viewerId = viewerId;
            viewerConnected(viewerId);
        }

        @Override
        public void onClosed(String reason) {
            sessions.remove(active);
            viewerClosed(reason);
        }
    }

    // -------------------------------------------------------------- registration

    private void startRegistration() {
        if (rendezvousServer == null || rendezvousServer.isEmpty() || rendezvousPort <= 0) {
            status("registration skipped: no rendezvous server configured");
            return;
        }
        byte[] signingPublicKey = Ed25519.publicKeyOf(hostSigningSeed);
        HostRegistration reg = new HostRegistration(rendezvousServer, rendezvousPort, hostId,
                signingPublicKey, new RegistrationListener());
        // HostRegistration's only channel for "here is my listening port" is the intranet answer
        // to FetchLocalAddr (PunchHoleSent echoes the controller's address and hbbs overwrites the
        // host address with the observed one), so setting the direct port here is what a
        // same-intranet controller is told; see the class report for the internet path.
        reg.setDirectPort(getListenPort());
        registration = reg;
        status("registration: hbbs " + rendezvousServer + ":" + rendezvousPort
                + ", intranet direct port " + getListenPort());
        reg.start();
    }

    private void stopRegistration() {
        HostRegistration reg = registration;
        registration = null;
        if (reg != null) {
            reg.stop();
        }
    }

    private final class RegistrationListener implements HostRegistration.Listener {
        @Override
        public void onStatus(String status) {
            HostAgent.this.status("[hbbs] " + status);
        }

        @Override
        public void onRegistered(String id) {
            HostAgent.this.status("[hbbs] registered id " + id);
        }

        @Override
        public void onClosed(String reason) {
            // The registration has its own reconnect loop; this only ends on stop(), so it stays a
            // log line and never reaches Listener.onViewerClosed.
            HostAgent.this.status("[hbbs] closed: " + reason);
        }
    }

    // ------------------------------------------------------------------ encoding

    /**
     * Encodes an address the way {@code hbb_common::AddrMangle::encode} does, into the byte blob
     * {@code socket_addr} carries in {@code PunchHole}, {@code PunchHoleSent} and {@code LocalAddr}.
     *
     * <p>Derivation, by inverting {@link com.rustdesk.java.net.AddrMangle#decode(byte[])}. That
     * decoder reads the blob as a little endian u128 and computes</p>
     *
     * <pre>
     *   tm   = (number &gt;&gt; 17) &amp; 0xFFFFFFFF
     *   ip   = ((number &gt;&gt; 49) - tm) as u32        // little endian IPv4
     *   port = ((number &amp; 0xFFFFFF) - (tm &amp; 0xFFFF)) as u16
     * </pre>
     *
     * <p>so the blob is</p>
     *
     * <pre>
     *   number = ((ip + tm) &lt;&lt; 49) | (tm &lt;&lt; 17) | (port + (tm &amp; 0xFFFF))
     * </pre>
     *
     * <p>with {@code tm} a 32 bit obfuscation term ({@code as_micros() as u32} upstream, picked so
     * a firewall that scans the packet does not see a local address). Field widths follow from the
     * inverse: bits 0..16 hold {@code port + (tm & 0xFFFF)} (65535 + 65535 still needs only 17
     * bits), bits 17..48 hold {@code tm}, and everything from bit 49 up is {@code ip + tm}.
     *
     * <p>Two consequences are easy to get wrong:</p>
     *
     * <ul>
     *   <li>{@code ip + tm} may need 33 bits. Rust keeps the sum in a u128, and decode's
     *       {@code (number &gt;&gt; 49) - tm} is exact before the {@code as u32} truncation, so the
     *       sum must not be wrapped to 32 bits when building the blob. This is why real hbbs blobs
     *       are often 11 bytes instead of 16: the highest set bit lands in byte 10.
     *   <li>decode masks {@code number} with 0xFFFFFF, i.e. 24 bits, so it also picks up the low
     *       7 bits of {@code tm} as {@code B * 2^17} with {@code B = tm &amp; 0x7F}. Rust's
     *       {@code port as u16} throws that term away; this repository's
     *       {@code net.AddrMangle.decode} passes the value straight to {@code InetSocketAddress}
     *       and therefore returns null instead. {@link #encode(InetSocketAddress)} keeps those 7
     *       bits of {@code tm} clear so its own output stays decodable here;
     *       {@link #encode(InetSocketAddress, long)} does not, and exists for the round trip
     *       proof.
     * </ul>
     *
     * <p>Trailing zero bytes are trimmed like upstream does, and an IPv6 address becomes the raw
     * 16 bytes followed by the little endian port (18 bytes), also like upstream.</p>
     */
    public static byte[] encode(InetSocketAddress addr) {
        // Micros since the epoch truncated to 32 bits is what Rust uses; clear the low 7 bits so
        // this JVM's own decoder can read the blob back (see the two consequences above).
        long tm = (System.currentTimeMillis() * 1000L) & 0xFFFFFFFFL & ~TM_LOW_BITS;
        return encode(addr, tm);
    }

    /**
     * Encodes with an explicit obfuscation term, for tests and for callers that need a
     * reproducible blob. {@code tm} is used as the low 32 bits; pass 0 for the layout
     * {@code HostRegistration} uses and anything else for what hbbs emits.
     */
    public static byte[] encode(InetSocketAddress addr, long tm) {
        if (addr == null) {
            throw new NullPointerException("addr");
        }
        InetAddress ip = addr.getAddress();
        if (ip == null) {
            throw new IllegalArgumentException("unresolved address: " + addr);
        }
        int port = addr.getPort();

        if (ip instanceof Inet4Address) {
            byte[] raw = ip.getAddress();
            long ipRaw = (raw[0] & 0xFFL)
                    | ((raw[1] & 0xFFL) << 8)
                    | ((raw[2] & 0xFFL) << 16)
                    | ((raw[3] & 0xFFL) << 24);
            long tm32 = tm & 0xFFFFFFFFL;
            long portTerm = (port & 0xFFFFL) + (tm32 & 0xFFFFL);
            BigInteger number = BigInteger.valueOf(ipRaw + tm32)
                    .shiftLeft(49)
                    .or(BigInteger.valueOf(tm32).shiftLeft(17))
                    .or(BigInteger.valueOf(portTerm));
            return trimTrailingZeros(toLittleEndian(number, 16));
        }

        byte[] out = new byte[18];
        System.arraycopy(ip.getAddress(), 0, out, 0, 16);
        out[16] = (byte) (port & 0xFF);
        out[17] = (byte) ((port >>> 8) & 0xFF);
        return out;
    }

    private static byte[] toLittleEndian(BigInteger number, int size) {
        byte[] be = number.toByteArray();
        // toByteArray() is minimal and big endian, with a sign byte when the top bit is set.
        int start = 0;
        while (start < be.length - 1 && be[start] == 0) {
            start++;
        }
        int length = be.length - start;
        if (length > size) {
            throw new IllegalArgumentException("address does not fit in " + size + " bytes");
        }
        byte[] out = new byte[size];
        for (int i = 0; i < length; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static byte[] trimTrailingZeros(byte[] bytes) {
        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == 0) {
            length--;
        }
        return length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
    }

    // ------------------------------------------------------------------- helpers

    private static String describe(SocketAddress address) {
        return address == null ? "(unknown)" : address.toString();
    }

    private void status(String text) {
        Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onStatus(text);
        } catch (RuntimeException ignored) {
            // a listener bug must not kill a session or the accept thread
        }
    }

    private void viewerConnected(String viewerId) {
        Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onViewerConnected(viewerId == null ? "" : viewerId);
        } catch (RuntimeException ignored) {
            // a listener bug must not kill a session
        }
    }

    private void viewerClosed(String reason) {
        Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onViewerClosed(reason == null ? "" : reason);
        } catch (RuntimeException ignored) {
            // a listener bug must not kill a session
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
