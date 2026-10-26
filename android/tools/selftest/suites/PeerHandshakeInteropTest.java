// Migrated from A:\Downloads\tmpscripts\hostsess\PeerHandshakeInteropTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.google.protobuf.ByteString;
import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.host.HostSession;
import com.rustdesk.java.net.StreamTransport;
import com.rustdesk.java.net.Transport;
import com.rustdesk.java.session.PeerHandshake;
import com.rustdesk.java.session.PeerIdentity;

import hbb.MessageOuterClass.EncodedVideoFrame;
import hbb.MessageOuterClass.Hash;
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.LoginResponse;
import hbb.MessageOuterClass.Message;
import hbb.Rendezvous.IdPk;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interop test: the repository's own viewer handshake drives the repository's host session.
 *
 * <p>Host side: {@link HostSession} on one end of a 127.0.0.1 loopback socket, with a fresh
 * curve25519 box key pair and a fresh ed25519 signing key pair. Viewer side: the real
 * {@link PeerHandshake#connect}, given the host's ed25519 public key the way it would receive it
 * from hbbs in {@code PunchHoleResponse.pk}. This is the pairing that used to deadlock: the host
 * waited for the viewer's {@code PublicKey} while the viewer waited for the host's
 * {@code SignedId}. No android.jar is loaded and no viewer is hand-written here.</p>
 */
public final class PeerHandshakeInteropTest {

    private static final String HOST_ID = "123456789";
    private static final String HOST_PASSWORD = "s3cret-pass";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final byte[][] FRAME_DATA = {
        pattern(0x11, 200),
        pattern(0x22, 4096),
        { 0x00, 0x00, 0x00, 0x01, 0x65, (byte) 0x88, (byte) 0x84, 0x00, 0x21 }
    };
    private static final boolean[] FRAME_KEY = { true, false, false };
    private static final long[] FRAME_PTS = { 0L, 33L, 1000L };

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        System.out.println("HostSession <-> PeerHandshake interop test "
                + "(real viewer, JVM only, no android.jar)");
        System.out.println();

        testFullSession();
        testWrongPassword();
        testWrongUsername();
        testLegacyConstructor();

        System.out.println();
        System.out.println("checks: " + checks + ", failures: " + failures);
        if (failures == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: FAILED (" + failures + " of " + checks + " checks)");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- cases

    private static void testFullSession() throws Exception {
        section("case 1: PeerHandshake viewer -> SECURED, login, 3 H.264 frames");
        Harness h = null;
        try {
            h = new Harness(false, new FakeFrames());
            PeerHandshake.Result result = h.connect();

            check("PeerHandshake returns SECURED", result.security == PeerHandshake.Security.SECURED,
                    String.valueOf(result.security));
            check("verified identity names the host", result.identity != null
                    && HOST_ID.equals(result.identity.id),
                    result.identity == null ? "(null)" : result.identity.id);
            check("identity.kxVersion is the host's advertised 1",
                    result.identity != null && result.identity.kxVersion == HostSession.HOST_KX_VERSION,
                    result.identity == null ? "(null)" : String.valueOf(result.identity.kxVersion));
            check("identity box public key == the host's curve25519 key",
                    result.identity != null && Arrays.equals(h.hostPk, result.identity.publicKey), "");
            check("transport is not the raw loopback stream", result.transport != h.viewerRaw, "");

            // The viewer consumed the host's first frame: it must have been SignedId.
            Message firstRaw = h.recorder.firstReceived == null
                    ? null : Message.parseFrom(h.recorder.firstReceived);
            check("host's very first wire frame is signed_id",
                    firstRaw != null && firstRaw.hasSignedId(),
                    firstRaw == null ? "(nothing received)" : String.valueOf(firstRaw.getUnionCase()));
            byte[] blob = firstRaw != null && firstRaw.hasSignedId()
                    ? firstRaw.getSignedId().getId().toByteArray() : new byte[0];
            check("signed_id.id is signature(64) || IdPk", blob.length > 64, blob.length + " bytes");
            IdPk idPk = null;
            try {
                idPk = IdPk.parseFrom(Ed25519.verify(blob, h.hostSigningPk));
            } catch (Exception e) {
                // reported below
            }
            check("SignedId verifies against the host ed25519 public key (the hbbs pk)", idPk != null,
                    idPk == null ? "signature mismatch" : "");
            check("IdPk.id == host id",
                    idPk != null && HOST_ID.equals(idPk.getId()),
                    idPk == null ? "(no IdPk)" : idPk.getId());
            check("IdPk.pk == host box public key",
                    idPk != null && Arrays.equals(h.hostPk, idPk.getPk().toByteArray()), "");
            check("IdPk.kx_version == 1",
                    idPk != null && idPk.getKxVersion() == 1,
                    idPk == null ? "(no IdPk)" : String.valueOf(idPk.getKxVersion()));

            Hash hash = h.awaitHash();
            check("host sends Hash after the key exchange",
                    hash.getSalt().length() == 32 && hash.getChallenge().length() == 32,
                    hash.getSalt() + "/" + hash.getChallenge());

            LoginResponse response = h.login(hash, HOST_ID, HOST_PASSWORD, "viewer-interop");
            check("LoginResponse has no error", !response.hasError(),
                    response.hasError() ? response.getError() : "");
            check("peer_info.platform == HostSession.PLATFORM",
                    response.hasPeerInfo()
                            && HostSession.PLATFORM.equals(response.getPeerInfo().getPlatform()),
                    response.hasPeerInfo() ? response.getPeerInfo().getPlatform() : "(no peer_info)");
            check("peer_info announces the 1280x720 display",
                    response.hasPeerInfo()
                            && response.getPeerInfo().getDisplaysCount() == 1
                            && response.getPeerInfo().getDisplays(0).getWidth() == 1280
                            && response.getPeerInfo().getDisplays(0).getHeight() == 720, "");
            check("host reports itself logged in", h.session.isLoggedIn(), "");
            check("listener.onLoginOk fired with the viewer id",
                    h.listener.loginOkCount.get() == 1 && "viewer-interop".equals(h.listener.loginViewerId),
                    "count=" + h.listener.loginOkCount.get() + " id=" + h.listener.loginViewerId);

            int framesSeen = 0;
            boolean framesOk = true;
            StringBuilder detail = new StringBuilder();
            while (framesSeen < FRAME_DATA.length) {
                Message message = h.receive();
                if (!message.hasVideoFrame() || !message.getVideoFrame().hasH264S()) {
                    framesOk = false;
                    detail.append("unexpected ").append(message.getUnionCase()).append(' ');
                    continue;
                }
                List<EncodedVideoFrame> list = message.getVideoFrame().getH264S().getFramesList();
                if (list.size() != 1) {
                    framesOk = false;
                    detail.append("frames=").append(list.size()).append(' ');
                    continue;
                }
                EncodedVideoFrame frame = list.get(0);
                boolean dataOk = Arrays.equals(FRAME_DATA[framesSeen], frame.getData().toByteArray());
                boolean keyOk = FRAME_KEY[framesSeen] == frame.getKey();
                boolean ptsOk = FRAME_PTS[framesSeen] == frame.getPts();
                if (!dataOk || !keyOk || !ptsOk) {
                    framesOk = false;
                    detail.append("frame#").append(framesSeen)
                            .append("[data=").append(dataOk)
                            .append(",key=").append(keyOk)
                            .append(",pts=").append(ptsOk).append("] ");
                }
                framesSeen++;
            }
            check("viewer receives 3 video_frame(h264s) messages", framesOk && framesSeen == 3,
                    detail.toString());

            h.session.stop();
            check("onClosed fires after stop()", h.listener.closed.await(3, TimeUnit.SECONDS), "");
            check("closed reason is \"stopped\"", "stopped".equals(h.listener.closedReason),
                    String.valueOf(h.listener.closedReason));
        } catch (Throwable t) {
            fail("case 1 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    private static void testWrongPassword() throws Exception {
        section("case 2: wrong password");
        Harness h = null;
        try {
            h = new Harness(false, null);
            PeerHandshake.Result result = h.connect();
            check("handshake still secures the stream", result.security == PeerHandshake.Security.SECURED,
                    String.valueOf(result.security));
            Hash hash = h.awaitHash();
            LoginResponse response = h.login(hash, HOST_ID, "definitely-wrong", "viewer-bad");
            check("error == \"Wrong Password\"",
                    response.hasError() && "Wrong Password".equals(response.getError()),
                    "error=\"" + response.getError() + "\"");
            check("session is not logged in", !h.session.isLoggedIn(), "");
            check("onClosed fires after the rejection", h.listener.closed.await(3, TimeUnit.SECONDS), "");
        } catch (Throwable t) {
            fail("case 2 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    private static void testWrongUsername() throws Exception {
        section("case 3: wrong username (right password)");
        Harness h = null;
        try {
            h = new Harness(false, null);
            h.connect();
            Hash hash = h.awaitHash();
            LoginResponse response = h.login(hash, "999999999", HOST_PASSWORD, "viewer-other");
            check("error == \"Offline\"",
                    response.hasError() && "Offline".equals(response.getError()),
                    "error=\"" + response.getError() + "\"");
            check("session is not logged in", !h.session.isLoggedIn(), "");
            check("onClosed fires after the rejection", h.listener.closed.await(3, TimeUnit.SECONDS), "");
        } catch (Throwable t) {
            fail("case 3 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    /** The 7-argument constructor (ephemeral signing key) must keep working end to end. */
    private static void testLegacyConstructor() throws Exception {
        section("case 4: legacy constructor still handshakes (ephemeral ed25519 key)");
        Harness h = null;
        try {
            h = new Harness(true, null);
            PeerHandshake.Result result = h.connect();
            check("legacy constructor still yields a SECURED session",
                    result.security == PeerHandshake.Security.SECURED,
                    String.valueOf(result.security));
            check("ephemeral signing key verifies SignedId and names the host",
                    result.identity != null && HOST_ID.equals(result.identity.id), "");
            check("getHostSigningPublicKey() matches the key that signed SignedId",
                    Arrays.equals(h.session.getHostSigningPublicKey(), h.viewerSigningKey), "");
            Hash hash = h.awaitHash();
            LoginResponse response = h.login(hash, HOST_ID, HOST_PASSWORD, "viewer-legacy");
            check("legacy constructor login succeeds", !response.hasError(),
                    response.hasError() ? response.getError() : "");
        } catch (Throwable t) {
            fail("case 4 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    // ------------------------------------------------------------- harness

    /** One loopback connection: HostSession on the host side, PeerHandshake on the viewer side. */
    private static final class Harness {
        final byte[] hostPk;
        final byte[] hostSk;
        final byte[] hostSigningPk;
        final byte[] hostSigningSeed;
        final byte[] viewerSigningKey;
        final ServerSocket server;
        final Socket hostSocket;
        final Socket viewerSocket;
        final RecordingTransport recorder;
        final RecordingListener listener = new RecordingListener();
        final HostSession session;
        final Transport viewerRaw;
        Transport viewerTransport;

        Harness(boolean legacy, HostSession.FrameSource source) throws Exception {
            byte[][] boxKeys = Sodium.genBoxKeyPair();
            hostPk = boxKeys[0];
            hostSk = boxKeys[1];
            byte[][] signingKeys = Ed25519.generateKeyPair();
            hostSigningSeed = signingKeys[1];

            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            viewerSocket = new Socket();
            viewerSocket.connect(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), server.getLocalPort()), 3000);
            viewerSocket.setSoTimeout(5000);
            viewerSocket.setTcpNoDelay(true);
            hostSocket = server.accept();
            hostSocket.setTcpNoDelay(true);

            if (legacy) {
                session = new HostSession(new StreamTransport(hostSocket), HOST_ID, HOST_PASSWORD,
                        hostPk, hostSk, listener, source);
                // What a viewer would have to learn out of band: the constructor's own key.
                hostSigningPk = session.getHostSigningPublicKey();
            } else {
                session = new HostSession(new StreamTransport(hostSocket), HOST_ID, HOST_PASSWORD,
                        hostPk, hostSk, hostSigningSeed, listener, source);
                hostSigningPk = signingKeys[0];
            }
            session.setDisplaySize(1280, 720);

            viewerRaw = new StreamTransport(viewerSocket);
            recorder = new RecordingTransport(viewerRaw);
            viewerSigningKey = hostSigningPk;
            session.start();
        }

        /** The viewer side, exactly as the app calls it after getting pk from hbbs. */
        PeerHandshake.Result connect() throws IOException {
            PeerHandshake.Result result = PeerHandshake.connect(recorder, HOST_ID,
                    PeerIdentity.decodeRsPublicKey(PeerIdentity.DEFAULT_RS_PUB_KEY),
                    viewerSigningKey);
            viewerTransport = result.transport;
            return result;
        }

        Hash awaitHash() throws IOException {
            Message message = receive();
            if (!message.hasHash()) {
                throw new IOException("expected Hash, got " + message.getUnionCase());
            }
            return message.getHash();
        }

        LoginResponse login(Hash hash, String username, String plainPassword, String viewerId)
                throws IOException {
            byte[] digest = passwordDigest(plainPassword, hash.getSalt(), hash.getChallenge());
            LoginRequest request = LoginRequest.newBuilder()
                    .setUsername(username)
                    .setPassword(ByteString.copyFrom(digest))
                    .setMyId(viewerId)
                    .setMyName("interop-viewer")
                    .setMyPlatform("Linux")
                    .setVersion("1.5.1")
                    .setSessionId(4242L)
                    .build();
            viewerTransport.send(Message.newBuilder().setLoginRequest(request).build().toByteArray());
            Message message = receive();
            if (!message.hasLoginResponse()) {
                throw new IOException("expected LoginResponse, got " + message.getUnionCase());
            }
            return message.getLoginResponse();
        }

        Message receive() throws IOException {
            byte[] frame = viewerTransport.receive();
            if (frame == null) {
                throw new EOFException("viewer: host closed the stream");
            }
            return Message.parseFrom(frame);
        }

        void close() {
            session.stop();
            viewerRaw.close();
            try {
                hostSocket.close();
            } catch (IOException ignored) {
                // best effort
            }
            try {
                server.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** Records the first frame the viewer reads, to prove SignedId really goes out first. */
    private static final class RecordingTransport implements Transport {
        private final Transport delegate;
        volatile byte[] firstReceived;

        RecordingTransport(Transport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void send(byte[] payload) throws IOException {
            delegate.send(payload);
        }

        @Override
        public byte[] receive() throws IOException {
            byte[] frame = delegate.receive();
            if (firstReceived == null && frame != null) {
                firstReceived = frame;
            }
            return frame;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class FakeFrames implements HostSession.FrameSource {
        private final byte[][] data = FRAME_DATA;
        private final boolean[] key = FRAME_KEY;
        private final long[] pts = FRAME_PTS;
        private int index;

        @Override
        public boolean nextFrame(HostSession.Frame out) {
            if (index >= data.length) {
                return false;
            }
            out.data = data[index];
            out.key = key[index];
            out.ptsMs = pts[index];
            index++;
            return true;
        }
    }

    private static final class RecordingListener implements HostSession.Listener {
        final List<String> statuses = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger loginOkCount = new AtomicInteger();
        final AtomicInteger closedCount = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile String loginViewerId;
        volatile String closedReason;

        @Override
        public void onStatus(String status) {
            statuses.add(status);
        }

        @Override
        public void onLoginOk(String viewerId) {
            loginViewerId = viewerId;
            loginOkCount.incrementAndGet();
        }

        @Override
        public void onClosed(String reason) {
            closedReason = reason;
            closedCount.incrementAndGet();
            closed.countDown();
        }
    }

    // ------------------------------------------------------------- helpers

    private static byte[] passwordDigest(String password, String salt, String challenge) {
        byte[] first = sha256(concat(password.getBytes(UTF8), salt.getBytes(UTF8)));
        return sha256(concat(first, challenge.getBytes(UTF8)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] pattern(int value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, (byte) value);
        out[0] = 0x00;
        out[1] = 0x00;
        out[2] = 0x00;
        out[3] = 0x01;
        return out;
    }

    private static void section(String title) {
        System.out.println("-- " + title);
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("[PASS] " + name);
        } else {
            failures++;
            System.out.println("[FAIL] " + name
                    + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    private static void fail(String name, Throwable t) {
        checks++;
        failures++;
        System.out.println("[FAIL] " + name);
        t.printStackTrace(System.out);
    }
}
