package com.rustdesk.java.host;

import com.google.protobuf.ByteString;
import com.rustdesk.java.net.RendezvousKeyExchange;
import com.rustdesk.java.net.StreamTransport;
import com.rustdesk.java.net.Transport;
import com.rustdesk.java.session.PeerIdentity;

import hbb.MessageOuterClass;
import hbb.Rendezvous.FetchLocalAddr;
import hbb.Rendezvous.LocalAddr;
import hbb.Rendezvous.PunchHole;
import hbb.Rendezvous.PunchHoleSent;
import hbb.Rendezvous.RegisterPk;
import hbb.Rendezvous.RegisterPkResponse;
import hbb.Rendezvous.RendezvousMessage;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers this machine (the controlled end) with a RustDesk rendezvous server (hbbs) so that
 * controllers can find it, and answers the punch-holes the server sends back.
 *
 * <p>WHY RegisterPeer IS NOT USED
 *
 * <p>The obvious-looking sequence KX -&gt; RegisterPk -&gt; RegisterPeer{id,serial} over the encrypted
 * TCP stream always ends with the server dropping the connection. hbbs's {@code handle_tcp} match
 * has no RegisterPeer arm at all, so it falls through to {@code _ => {}} and returns {@code false},
 * and the caller treats that as "break": the stream is closed without a reply. Serial value, order
 * of messages, reconnects and key-confirmation state make no difference.
 *
 * <p>WHAT REGISTERS A HOST INSTEAD
 *
 * <p>{@code RegisterPk} on the encrypted TCP stream is the registration. hbbs answers
 * {@code RegisterPkResponse{OK, keep_alive}}, records pk/uuid for the id and treats this connection
 * as the peer's rendezvous address. A controller's PunchHoleRequest is then answered by sending
 * {@code PunchHole} <b>back over this stream</b>; this class replies {@code PunchHoleSent} on the
 * same stream and the server forwards the host's pk + address to the controller. That whole cycle
 * is verified against rs-ny.rustdesk.com: controller gets
 * {@code PunchHoleResponse{addr, pk=109B signed IdPk}} and the host really does receive the punch.
 *
 * <p>After a PunchHoleSent the server closes the stream (the {@code PunchHoleSent}/{@code LocalAddr}
 * arms of {@code handle_tcp} do not return true). That is normal, not an error: this class
 * reconnects immediately and registers again, which is also why it must never mix a UDP
 * registration with this stream - dropping this stream removes the peer on the server, so a
 * concurrent UDP registration would be cancelled by every reconnect.
 *
 * <p>TestDelay is a session-layer message ({@code hbb.Message}), not a rendezvous message, so hbbs
 * never sends one here; the frame handler still echoes it if it ever arrives. ConfigUpdate is
 * ignored as the client does. {@code TestNatRequest} must never be used as a heartbeat: its
 * {@code handle_tcp} arm also fails to return true, so the server answers and then drops the
 * stream (measured: EOF 2.6 s after one TestNatRequest, while the same stream idle for 20 s stays
 * open). Repeated {@code RegisterPk} is what keeps both the stream and the peer entry alive.
 *
 * <p>Only JDK APIs are used, so this class runs unchanged on a plain JVM.
 */
public final class HostRegistration {

    /** Callback surface. Invoked on the registration worker thread, never on the caller's. */
    public interface Listener {
        void onStatus(String s);
        void onRegistered(String id);
        void onClosed(String reason);
    }

    /** Version string reported to the rendezvous server (informational). */
    public static final String VERSION = "1.5.1";

    /** RustDesk's direct-access port, derived like the client (RENDEZVOUS_PORT + 2). */
    public static final int DEFAULT_DIRECT_PORT = 21118;

    /**
     * Re-send RegisterPk this often. It is the keep-alive: hbbs drops a quiet peer after
     * REG_TIMEOUT = 30 s, and an untouched stream is not refreshed by anything else. The interval
     * must also stay above hbbs's 6 s RegisterPk throttle window.
     */
    static final long REGISTER_INTERVAL_MS = 10000L;
    /** isRegistered() goes false once the last RegisterPkResponse is older than this. */
    static final long STALE_AFTER_MS = 25000L;
    /** Reconnect delay: short after a punch, longer after repeated failures. */
    static final long RECONNECT_MIN_MS = 300L;
    static final long RECONNECT_MAX_MS = 6000L;
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int RECV_TIMEOUT_MS = 2000;

    private final String rendezvousServer;
    private final int port;
    private final String hostId;
    private final byte[] signPublicKey;
    private final Listener listener;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean closedNotified = new AtomicBoolean(false);
    private final AtomicBoolean registeredNotified = new AtomicBoolean(false);

    private volatile boolean registered = false;
    private volatile long lastPkOkMs = 0L;
    private volatile long lastRegisteredNotifyMs = 0L;
    private volatile Throwable failure = null;
    private volatile String lastServerMessage = "";
    private volatile Transport tcp = null;
    private volatile Socket tcpSocket = null;
    private volatile Thread worker = null;
    private volatile byte[] serverPublicKey = null;
    private volatile int directPort = DEFAULT_DIRECT_PORT;

    public HostRegistration(String rendezvousServer, int port, String hostId,
                            byte[] signPublicKey, Listener listener) {
        this.rendezvousServer = rendezvousServer;
        this.port = port;
        this.hostId = hostId;
        this.signPublicKey = signPublicKey == null ? null : signPublicKey.clone();
        this.listener = listener;
    }

    /**
     * Overrides the rendezvous server public key used to verify its KeyExchange signature.
     * Defaults to the official RustDesk key; set it for a self-hosted hbbs.
     */
    public void setServerPublicKey(String base64) {
        this.serverPublicKey = base64 == null || base64.isEmpty()
                ? null : PeerIdentity.decodeRsPublicKey(base64);
    }

    /** Overrides the TCP port advertised to intranet controllers (default 21118). */
    public void setDirectPort(int directPort) {
        if (directPort > 0 && directPort < 65536) {
            this.directPort = directPort;
        }
    }

    /** Starts the registration worker. Idempotent. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            status("start() ignored: already started");
            return;
        }
        stopping.set(false);
        final byte[] pk = signPublicKey;
        if (hostId == null || hostId.isEmpty()) {
            fail("host id is empty");
            return;
        }
        if (pk == null || pk.length != 32) {
            fail("sign public key must be 32 bytes (Ed25519), got "
                    + (pk == null ? "null" : pk.length + " bytes"));
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "hbbs-registration");
        t.setDaemon(true);
        worker = t;
        t.start();
        status("started: id=" + hostId + " server=" + rendezvousServer + ":" + port
                + " (registering with RegisterPk on the encrypted stream)");
    }

    /** Stops the worker and closes the stream. */
    public void stop() {
        if (!started.get()) {
            return;
        }
        if (stopping.compareAndSet(false, true)) {
            status("stop() requested");
        }
        registered = false;
        closeQuietly(tcp);
        closeQuietly(tcpSocket);
        Thread t = worker;
        if (t != null && t != Thread.currentThread()) {
            joinQuietly(t, 1500);
        }
        notifyClosed("stopped");
    }

    /** True while the server has acknowledged our registration inside REG_TIMEOUT. */
    public boolean isRegistered() {
        return registered && (System.currentTimeMillis() - lastPkOkMs) <= STALE_AFTER_MS;
    }

    /** Reason of the last terminal failure, or null. */
    public Throwable getFailure() {
        return failure;
    }

    /** Last server message summary, for logs and diagnostics. */
    public String getLastServerMessage() {
        return lastServerMessage;
    }

    // ------------------------------------------------------------- main loop

    private void loop() {
        long backoff = RECONNECT_MIN_MS;
        while (!stopping.get()) {
            Socket socket = null;
            Transport transport = null;
            boolean sawTraffic = false;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(
                        InetAddress.getByName(rendezvousServer), port), CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(RECV_TIMEOUT_MS);
                tcpSocket = socket;
                Transport raw = new StreamTransport(socket);
                transport = RendezvousKeyExchange.secure(raw, resolveServerKey());
                tcp = transport;
                lastServerMessage = "";
                status("connected to " + rendezvousServer + ":" + port + ", "
                        + (transport != raw ? "server key verified" : "no key exchange performed"));

                send(transport, registerPkMessage(), "RegisterPk");
                long lastRegister = System.currentTimeMillis();
                boolean punchSent = false;

                while (!stopping.get()) {
                    long now = System.currentTimeMillis();
                    if (registered && (now - lastPkOkMs) > STALE_AFTER_MS) {
                        registered = false;
                        status("registration went stale (no RegisterPkResponse for "
                                + ((now - lastPkOkMs) / 1000) + " s)");
                    }
                    if (now - lastRegister >= REGISTER_INTERVAL_MS) {
                        send(transport, registerPkMessage(), "RegisterPk(refresh)");
                        lastRegister = now;
                    }
                    byte[] frame;
                    try {
                        frame = transport.receive();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (frame == null) {
                        if (punchSent) {
                            status("stream closed after PunchHoleSent (normal); reconnecting");
                        } else {
                            status("stream closed by the server; reconnecting");
                        }
                        break;
                    }
                    sawTraffic = true;
                    if (frame.length == 0) {
                        // hbbs heartbeats with an empty frame; mirror it like the client does.
                        transport.send(new byte[0]);
                        continue;
                    }
                    if (handleFrame(transport, frame)) {
                        punchSent = true;
                    }
                }
            } catch (Exception e) {
                if (!stopping.get()) {
                    status("connection problem: " + e);
                }
            } finally {
                closeQuietly(transport);
                closeQuietly(socket);
                tcp = null;
                tcpSocket = null;
            }
            if (stopping.get()) {
                break;
            }
            // A stream that carried traffic (a punch, usually) is re-established at once so the
            // host is discoverable again as fast as possible; repeated dial failures back off.
            backoff = sawTraffic ? RECONNECT_MIN_MS : Math.min(backoff * 2, RECONNECT_MAX_MS);
            sleepQuietly(backoff);
        }
        registered = false;
        status("worker stopped");
        notifyClosed("stopped");
    }

    /** Handles one server frame. Returns true when it was a PunchHole we answered. */
    private boolean handleFrame(Transport transport, byte[] frame) {
        RendezvousMessage m = null;
        try {
            m = RendezvousMessage.parseFrom(frame);
        } catch (Exception ignored) {
            // not a rendezvous frame; maybe a session-layer message, handled below
        }
        if (m == null || m.getUnionCase() == RendezvousMessage.UnionCase.UNION_NOT_SET) {
            echoTestDelay(transport, frame);
            return false;
        }
        switch (m.getUnionCase()) {
            case REGISTER_PK_RESPONSE: {
                RegisterPkResponse r = m.getRegisterPkResponse();
                lastServerMessage = "RegisterPkResponse{result=" + r.getResult()
                        + ",keep_alive=" + r.getKeepAlive() + "}";
                if (r.getResult() == RegisterPkResponse.Result.OK) {
                    failure = null;
                    lastPkOkMs = System.currentTimeMillis();
                    registered = true;
                    status("registration accepted (keep_alive=" + r.getKeepAlive() + " s)");
                    long now = System.currentTimeMillis();
                    if (!registeredNotified.get()
                            || now - lastRegisteredNotifyMs > 60000L) {
                        registeredNotified.set(true);
                        lastRegisteredNotifyMs = now;
                        notifyRegistered(hostId);
                    }
                } else {
                    failure = new IllegalStateException(
                            "hbbs refused RegisterPk for id " + hostId + ": " + r.getResult()
                            + (r.getResult() == RegisterPkResponse.Result.UUID_MISMATCH
                            ? " (the id is already bound to another uuid/key pair)" : ""));
                    registered = false;
                    status("FAILED: " + failure.getMessage());
                }
                return false;
            }
            case PUNCH_HOLE: {
                PunchHole ph = m.getPunchHole();
                send(transport, RendezvousMessage.newBuilder()
                        .setPunchHoleSent(PunchHoleSent.newBuilder()
                                .setSocketAddr(ph.getSocketAddr())
                                .setId(hostId)
                                .setRelayServer(ph.getRelayServer())
                                .setNatType(ph.getNatType())
                                .setVersion(VERSION)
                                .build())
                        .build(), "PunchHoleSent");
                status("PunchHole answered (relay="
                        + (ph.getRelayServer().isEmpty() ? "none" : ph.getRelayServer()) + ")");
                return true;
            }
            case FETCH_LOCAL_ADDR: {
                RendezvousMessage reply = localAddrMessage(m.getFetchLocalAddr());
                if (reply == null) {
                    status("FetchLocalAddr: no intranet IPv4 address to advertise");
                } else {
                    send(transport, reply, "LocalAddr");
                    status("FetchLocalAddr answered (same-intranet controller)");
                }
                return false;
            }
            case CONFIGURE_UPDATE:
                lastServerMessage = "ConfigUpdate{serial="
                        + m.getConfigureUpdate().getSerial() + "}";
                status("ConfigUpdate ignored (serial="
                        + m.getConfigureUpdate().getSerial() + ")");
                return false;
            case TEST_NAT_RESPONSE:
                return false;
            case REGISTER_PEER_RESPONSE:
                // Should never happen: RegisterPeer is not part of this channel.
                status("unexpected RegisterPeerResponse ignored");
                return false;
            default:
                status("ignored " + m.getUnionCase());
                return false;
        }
    }

    /**
     * TestDelay belongs to the session protocol (hbb.Message), not the rendezvous protocol, so hbbs
     * never sends it here. If one is piped through anyway, echo it so the latency probe is answered.
     */
    private void echoTestDelay(Transport transport, byte[] frame) {
        try {
            MessageOuterClass.Message msg = MessageOuterClass.Message.parseFrom(frame);
            if (msg.hasTestDelay()) {
                transport.send(msg.toByteArray());
                status("TestDelay echoed back");
            } else {
                status("ignored undecodable frame (" + frame.length + " B)");
            }
        } catch (Exception e) {
            status("ignored undecodable frame (" + frame.length + " B)");
        }
    }

    // --------------------------------------------------------------- helpers

    private RendezvousMessage registerPkMessage() {
        return RendezvousMessage.newBuilder()
                .setRegisterPk(RegisterPk.newBuilder()
                        .setId(hostId)
                        .setUuid(ByteString.copyFrom(deriveUuid()))
                        .setPk(ByteString.copyFrom(signPublicKey))
                        .setNoRegisterDevice(true)
                        .build())
                .build();
    }

    private RendezvousMessage localAddrMessage(FetchLocalAddr fla) {
        List<InetSocketAddress> locals = localIPv4Addresses();
        if (locals.isEmpty()) {
            return null;
        }
        // hbbs copies local_addr verbatim into PunchHoleResponse.socket_addr, which the controller
        // decodes as one AddrMangle blob, so a single address is advertised.
        InetSocketAddress withPort =
                new InetSocketAddress(locals.get(0).getAddress(), directPort);
        return RendezvousMessage.newBuilder()
                .setLocalAddr(LocalAddr.newBuilder()
                        .setSocketAddr(fla.getSocketAddr())
                        .setLocalAddr(ByteString.copyFrom(encodeAddr(withPort)))
                        .setRelayServer(fla.getRelayServer())
                        .setId(hostId)
                        .setVersion(VERSION)
                        .build())
                .build();
    }

    private void send(Transport transport, RendezvousMessage m, String label) {
        try {
            transport.send(m.toByteArray());
            status("sent " + label);
        } catch (Exception e) {
            status("send " + label + " failed: " + e);
        }
    }

    private byte[] resolveServerKey() {
        byte[] key = serverPublicKey;
        return key != null ? key : PeerIdentity.decodeRsPublicKey(PeerIdentity.DEFAULT_RS_PUB_KEY);
    }

    /**
     * Stable 16-byte device uuid derived from id + public key, so a restart keeps the same uuid;
     * hbbs answers UUID_MISMATCH when it changes for an id it already knows.
     */
    private byte[] deriveUuid() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(hostId.getBytes("UTF-8"));
            md.update((byte) 0);
            md.update(signPublicKey);
            byte[] digest = md.digest();
            byte[] uuid = new byte[16];
            System.arraycopy(digest, 0, uuid, 0, 16);
            return uuid;
        } catch (Exception e) {
            byte[] uuid = new byte[16];
            System.arraycopy(signPublicKey, 0, uuid, 0, 16);
            return uuid;
        }
    }

    /**
     * Encodes an address exactly as hbb_common's AddrMangle.decode expects: a little endian u128
     * with the port in bits 0..15 and the little endian IPv4 address in bits 49..80. The 32 bit
     * obfuscation term at bits 17..48 is left at zero, which decodes identically.
     */
    static byte[] encodeAddr(InetSocketAddress addr) {
        if (addr.getAddress() instanceof Inet4Address) {
            byte[] raw = addr.getAddress().getAddress();
            long ipRaw = (raw[0] & 0xFFL)
                    | ((raw[1] & 0xFFL) << 8)
                    | ((raw[2] & 0xFFL) << 16)
                    | ((raw[3] & 0xFFL) << 24);
            byte[] out = new byte[16];
            int p = addr.getPort();
            out[0] = (byte) (p & 0xFF);
            out[1] = (byte) ((p >>> 8) & 0xFF);
            for (int k = 0; k < 32; k++) {
                if (((ipRaw >>> k) & 1L) != 0L) {
                    int bit = 49 + k;
                    out[bit / 8] |= (byte) (1 << (bit % 8));
                }
            }
            return out;
        }
        byte[] out = new byte[18];
        System.arraycopy(addr.getAddress().getAddress(), 0, out, 0, 16);
        out[16] = (byte) (addr.getPort() & 0xFF);
        out[17] = (byte) ((addr.getPort() >>> 8) & 0xFF);
        return out;
    }

    /** Non-loopback site-local IPv4 addresses, used to answer FetchLocalAddr. */
    static List<InetSocketAddress> localIPv4Addresses() {
        List<InetSocketAddress> out = new ArrayList<InetSocketAddress>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                try {
                    if (!nic.isUp() || nic.isLoopback()) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                List<InterfaceAddress> addrs = nic.getInterfaceAddresses();
                for (InterfaceAddress ia : addrs) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()
                            && !a.isLoopbackAddress()) {
                        out.add(new InetSocketAddress(a, 0));
                    }
                }
            }
        } catch (Exception e) {
            // having no intranet address is a normal condition, not an error
        }
        return out;
    }

    private void status(String s) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onStatus(s);
        } catch (RuntimeException ignored) {
            // a listener bug must not kill the worker
        }
    }

    private void notifyRegistered(String id) {
        final Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onRegistered(id);
        } catch (RuntimeException ignored) {
            // a listener bug must not kill the worker
        }
    }

    private void notifyClosed(String reason) {
        if (!closedNotified.compareAndSet(false, true)) {
            return;
        }
        final Listener l = listener;
        if (l == null) {
            return;
        }
        try {
            l.onClosed(reason);
        } catch (RuntimeException ignored) {
            // a listener bug must not kill the worker
        }
    }

    private void fail(String reason) {
        failure = new IllegalStateException(reason);
        registered = false;
        status("FAILED: " + reason);
        if (stopping.compareAndSet(false, true)) {
            closeQuietly(tcp);
            closeQuietly(tcpSocket);
            notifyClosed(reason);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Exception ignored) {
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

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
