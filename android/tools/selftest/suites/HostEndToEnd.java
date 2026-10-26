// Source: tmpscripts/e2e/HostEndToEnd.java (migrated into the repository so the host path stays
// covered). Needs a real rendezvous server and therefore runs only with -IncludeNetwork.

package com.rustdesk.java.selftest;
import com.google.protobuf.ByteString;
import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.host.HostAgent;
import com.rustdesk.java.net.AddrMangle;
import com.rustdesk.java.net.RelayClient;
import com.rustdesk.java.net.RendezvousClient;
import com.rustdesk.java.net.StreamTransport;
import com.rustdesk.java.net.Transport;
import com.rustdesk.java.session.InputMessages;
import com.rustdesk.java.session.PeerHandshake;
import com.rustdesk.java.session.PeerIdentity;

import hbb.MessageOuterClass.Hash;
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.LoginResponse;
import hbb.MessageOuterClass.Message;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end over the real public rendezvous server: this repository's host registers and listens,
 * then this repository's viewer finds it via PunchHole, handshakes, logs in and sends input.
 *
 * <p>This is the only test that exercises registration, address distribution, the direct
 * connection, the two-level identity chain and the login digest together without a phone.
 *
 * <p>The rendezvous server is a public shared service, so a dropped SYN is normal rather than a
 * code fault. Resolving and connecting therefore retry with a short backoff before the suite is
 * allowed to fail; the assertions themselves are unchanged.
 */
public class HostEndToEnd {

    private static final String DEFAULT_SERVER = "rs-ny.rustdesk.com";
    private static final int PORT = 21116;
    private static final String PASSWORD = "e2e-secret-123";

    /** resolve() gets three attempts; the server can swallow the first connect attempt. */
    private static final int RESOLVE_ATTEMPTS = 3;
    private static final long[] RESOLVE_BACKOFF_MS = {2_000L, 4_000L};
    /** The direct dial and the relay leg get one retry each. */
    private static final int CONNECT_ATTEMPTS = 2;
    private static final long CONNECT_BACKOFF_MS = 2_000L;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private static int failures;

    public static void main(String[] args) throws Exception {
        boolean serverFromArgs = args.length > 0 && args[0] != null && !args[0].trim().isEmpty();
        String server = serverFromArgs ? args[0].trim() : DEFAULT_SERVER;
        System.out.println("  server: " + server + ":" + PORT
                + (serverFromArgs ? " (args[0])" : " (default)"));

        String hostId = randomDigits(9);
        byte[][] boxKeys = Sodium.genBoxKeyPair();
        byte[][] signKeys = Ed25519.generateKeyPair();       // {pk, seed}
        byte[] hostSignPk = signKeys[0];
        byte[] hostSignSeed = signKeys[1];

        final CountDownLatch registered = new CountDownLatch(1);
        final CountDownLatch viewerConnected = new CountDownLatch(1);
        final AtomicReference<String> lastStatus = new AtomicReference<>("");

        HostAgent agent = new HostAgent(hostId, PASSWORD, server, PORT,
                boxKeys[0], boxKeys[1], hostSignSeed, new HostAgent.Listener() {
                    @Override
                    public void onStatus(String status) {
                        lastStatus.set(status);
                        System.out.println("  [host] " + status);
                    }

                    @Override
                    public void onViewerConnected(String viewerId) {
                        System.out.println("  [host] viewer connected: " + viewerId);
                        viewerConnected.countDown();
                    }

                    @Override
                    public void onViewerClosed(String reason) {
                        System.out.println("  [host] viewer closed: " + reason);
                    }
                });

        long step = System.nanoTime();
        agent.start();
        check("host bound a listening port", agent.getListenPort() > 0);
        System.out.println("  host id=" + hostId + " listenPort=" + agent.getListenPort());
        timing("host start", step);

        // Registration happens on its own thread; give it a few seconds.
        step = System.nanoTime();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline && !agent.isRegistered()) {
            Thread.sleep(200);
        }
        check("host registered with hbbs", agent.isRegistered());
        timing("host registration", step);
        registered.countDown();

        byte[] rsKey = PeerIdentity.decodeRsPublicKey(PeerIdentity.DEFAULT_RS_PUB_KEY);
        byte[] viewerSign = Ed25519.generateKeyPair()[0];
        String viewerId = randomDigits(9);

        RendezvousClient rendezvous = new RendezvousClient(server, PORT, "1.5.1");
        step = System.nanoTime();
        RendezvousClient.PeerAddress addr = resolveWithRetry(rendezvous, viewerId, viewerSign,
                hostId, rsKey);
        timing("rendezvous resolve (total)", step);
        System.out.println("  punch: direct=" + addr.direct + " relay=" + addr.relayServer
                + " peerPk=" + (addr.peerPk == null ? 0 : addr.peerPk.length) + "B");
        check("server returned the host identity", addr.peerPk != null && addr.peerPk.length > 0);
        check("server returned a reachable address", addr.hasDirect() || addr.hasRelay());

        step = System.nanoTime();
        Transport raw;
        if (addr.hasDirect()) {
            Socket socket = connectDirect(addr.direct);
            raw = new StreamTransport(socket);
            System.out.println("  direct connect ok to " + addr.direct);
        } else {
            raw = openRelay(addr.relayServer, hostId, addr.uuid);
            System.out.println("  relay connect ok via " + addr.relayServer);
        }
        timing("peer connection", step);

        // The key inside the server-signed blob is the host's ed25519 key, which must match the one
        // the agent registered with; that is what makes the viewer's SignedId check meaningful.
        step = System.nanoTime();
        PeerIdentity fromServer = PeerIdentity.verify(addr.peerPk, rsKey);
        check("server-signed identity names the host", hostId.equals(fromServer.id));
        check("server-signed key is the host ed25519 key",
                java.util.Arrays.equals(fromServer.publicKey, hostSignPk));
        timing("identity verification", step);

        step = System.nanoTime();
        PeerHandshake.Result hs = PeerHandshake.connect(raw, hostId, rsKey, fromServer.publicKey);
        check("handshake secured", hs.security == PeerHandshake.Security.SECURED);
        check("peer identity verified", hs.identity != null && hostId.equals(hs.identity.id));
        timing("peer handshake", step);

        Transport secured = hs.transport;
        step = System.nanoTime();
        Message first = Message.parseFrom(waitFor(secured, 15_000));
        check("host sent Hash", first.hasHash());
        timing("hash round trip", step);
        Hash hash = first.getHash();
        byte[] salt = hash.getSalt().getBytes(StandardCharsets.UTF_8);
        byte[] challenge = hash.getChallenge().getBytes(StandardCharsets.UTF_8);
        byte[] digest = sha256(concat(sha256(concat(
                PASSWORD.getBytes(StandardCharsets.UTF_8), salt)), challenge));

        step = System.nanoTime();
        secured.send(Message.newBuilder().setLoginRequest(LoginRequest.newBuilder()
                .setUsername(hostId)
                .setMyId(viewerId)
                .setMyName("e2e")
                .setMyPlatform("Android")
                .setVersion("1.5.1")
                .setPassword(ByteString.copyFrom(digest))
                .build()).build().toByteArray());

        Message response = Message.parseFrom(waitFor(secured, 15_000));
        check("host sent LoginResponse", response.hasLoginResponse());
        LoginResponse lr = response.getLoginResponse();
        if (lr.hasError()) {
            System.out.println("  login error: " + lr.getError());
        }
        check("login accepted", !lr.hasError());
        timing("login round trip", step);

        step = System.nanoTime();
        check("host reported the viewer connected",
                viewerConnected.await(5, TimeUnit.SECONDS));
        check("host announces a display", lr.hasPeerInfo() && lr.getPeerInfo().getDisplaysCount() > 0);
        timing("viewer connect notify", step);

        // Input must be accepted without tearing the session down.
        step = System.nanoTime();
        secured.send(InputMessages.mouseMove(100, 120).toByteArray());
        secured.send(InputMessages.mouseDown(100, 120, 1).toByteArray());
        secured.send(InputMessages.mouseUp(100, 120, 1).toByteArray());
        secured.send(InputMessages.text("e2e").toByteArray());
        Thread.sleep(500);
        check("session survived input", agent.getViewerCount() >= 1);
        timing("input round trip", step);

        step = System.nanoTime();
        secured.close();
        agent.stop();
        check("host stopped cleanly", !agent.isRunning());
        timing("shutdown", step);

        System.out.println(failures == 0 ? "RESULT: ALL PASSED" : "RESULT: " + failures + " FAILED");
        if (failures != 0) System.exit(1);
    }

    /**
     * Resolves the peer, retrying the whole TCP exchange. A failed attempt closes its own socket
     * inside {@link RendezvousClient#resolve} (finally block), so a retry cannot leak a connection.
     */
    private static RendezvousClient.PeerAddress resolveWithRetry(
            RendezvousClient rendezvous, String viewerId, byte[] viewerSign, String hostId,
            byte[] rsKey) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= RESOLVE_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try {
                RendezvousClient.PeerAddress addr =
                        rendezvous.resolve(viewerId, viewerSign, hostId, rsKey);
                System.out.println("  [time] resolve attempt " + attempt + ": "
                        + elapsedMs(start) + " ms");
                if (attempt > 1) {
                    System.out.println("  [retry] rendezvous recovered on attempt " + attempt);
                }
                return addr;
            } catch (IOException e) {
                last = e;
                System.out.println("  [retry] rendezvous attempt " + attempt + " failed: "
                        + describe(e) + " (" + elapsedMs(start) + " ms)");
                if (attempt < RESOLVE_ATTEMPTS) {
                    long delay = RESOLVE_BACKOFF_MS[attempt - 1];
                    System.out.println("  [retry] sleeping " + delay + " ms before rendezvous attempt "
                            + (attempt + 1));
                    sleep(delay);
                }
            }
        }
        throw new IOException("rendezvous resolve failed after " + RESOLVE_ATTEMPTS
                + " attempts: " + describe(last), last);
    }

    /** Dials the address the server handed out, retrying once when the first SYN is dropped. */
    private static Socket connectDirect(InetSocketAddress address) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            Socket socket = new Socket();
            try {
                socket.connect(address, CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                System.out.println("  [time] direct connect attempt " + attempt + ": "
                        + elapsedMs(start) + " ms");
                if (attempt > 1) {
                    System.out.println("  [retry] direct connect recovered on attempt " + attempt);
                }
                return socket;
            } catch (IOException e) {
                last = e;
                closeQuietly(socket);
                System.out.println("  [retry] direct connect attempt " + attempt + " failed: "
                        + describe(e) + " (" + elapsedMs(start) + " ms)");
                if (attempt < CONNECT_ATTEMPTS) {
                    System.out.println("  [retry] sleeping " + CONNECT_BACKOFF_MS
                            + " ms before direct connect attempt " + (attempt + 1));
                    sleep(CONNECT_BACKOFF_MS);
                }
            }
        }
        throw new IOException("direct connect failed after " + CONNECT_ATTEMPTS
                + " attempts: " + describe(last), last);
    }

    /** Opens the relay leg, retrying once; the pairing uuid is reusable for the same attempt set. */
    private static Transport openRelay(String relayServer, String peerId, String uuid)
            throws IOException {
        String relayHost = RelayClient.hostOf(relayServer);
        int relayPort = RelayClient.portOf(relayServer, RelayClient.RELAY_PORT);
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
            long start = System.nanoTime();
            try {
                Transport transport = RelayClient.open(relayHost, relayPort, peerId, uuid);
                System.out.println("  [time] relay connect attempt " + attempt + ": "
                        + elapsedMs(start) + " ms");
                if (attempt > 1) {
                    System.out.println("  [retry] relay connect recovered on attempt " + attempt);
                }
                return transport;
            } catch (IOException e) {
                last = e;
                System.out.println("  [retry] relay connect attempt " + attempt + " failed: "
                        + describe(e) + " (" + elapsedMs(start) + " ms)");
                if (attempt < CONNECT_ATTEMPTS) {
                    System.out.println("  [retry] sleeping " + CONNECT_BACKOFF_MS
                            + " ms before relay connect attempt " + (attempt + 1));
                    sleep(CONNECT_BACKOFF_MS);
                }
            }
        }
        throw new IOException("relay connect failed after " + CONNECT_ATTEMPTS
                + " attempts: " + describe(last), last);
    }

    private static byte[] waitFor(Transport transport, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frame = transport.receive();
            if (frame == null) throw new IllegalStateException("stream closed");
            return frame;
        }
        throw new IllegalStateException("timeout");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static void timing(String label, long startNanos) {
        System.out.println("  [time] " + label + ": " + elapsedMs(startNanos) + " ms");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + label);
        if (!ok) failures++;
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

    private static String randomDigits(int count) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }
}
