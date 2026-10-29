// Migrated from A:\Downloads\tmpscripts\hostsess\HostSessionTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.google.protobuf.ByteString;
import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Encrypt;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.host.HostSession;
import com.rustdesk.java.net.EncryptCipher;
import com.rustdesk.java.net.SecureTransport;
import com.rustdesk.java.net.StreamTransport;
import com.rustdesk.java.net.Transport;

import hbb.MessageOuterClass.EncodedVideoFrame;
import hbb.MessageOuterClass.Hash;
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.LoginResponse;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.PublicKey;
import hbb.MessageOuterClass.TestDelay;
import hbb.Rendezvous.IdPk;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HostSession loopback test with a hand-written viewer.
 *
 * <p>The host runs {@link HostSession} on a 127.0.0.1 loopback socket; the viewer is written here
 * by hand. The viewer now expects the host to speak first: it reads and verifies the host's
 * {@code SignedId} before it sends {@code PublicKey} with the sealed stream key. See
 * {@code PeerHandshakeInteropTest} for the same session driven by the repository's real viewer
 * handshake. No android.jar is loaded.</p>
 */
public final class HostSessionTest {

    private static final String HOST_ID = "123456789";
    private static final String HOST_PASSWORD = "s3cret-pass";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int KX_LATEST = 1;

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
        System.out.println("HostSession loopback test (hand-written viewer, JVM only, no android.jar)");
        System.out.println();

        testHandshakeLoginStream();
        testWrongPassword();
        testWrongUsername();
        testKxVersionZero();
        testStopUnblocksPendingReceive();
        testPreLoginTestDelayEcho();
        testNonKeyFirstFrameIsRejected();

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

    private static void testHandshakeLoginStream() throws Exception {
        section("case 1: host-first SignedId + handshake + login + 3 H.264 frames");
        Harness h = null;
        try {
            h = new Harness(HOST_ID, HOST_PASSWORD, new FakeFrames(FRAME_DATA, FRAME_KEY, FRAME_PTS));
            final Harness harness = h;
            h.sendPublicKey(KX_LATEST);
            checkSignedId(h, "case 1");

            Hash hash = h.awaitHash();
            check("host sends Hash with 16-byte salt (32 hex chars)",
                    hash.getSalt().length() == 32 && isHex(hash.getSalt()), hash.getSalt());
            check("host sends Hash with 16-byte challenge (32 hex chars)",
                    hash.getChallenge().length() == 32 && isHex(hash.getChallenge()), hash.getChallenge());

            LoginResponse response = h.sendLogin(hash, HOST_ID, HOST_PASSWORD, "viewer-42");
            check("LoginResponse has no error", !response.hasError(),
                    response.hasError() ? response.getError() : "");
            check("peer_info.platform is non-empty",
                    response.hasPeerInfo() && !response.getPeerInfo().getPlatform().isEmpty(),
                    response.hasPeerInfo() ? "\"" + response.getPeerInfo().getPlatform() + "\"" : "(no peer_info)");
            check("peer_info.platform == HostSession.PLATFORM",
                    response.hasPeerInfo()
                            && HostSession.PLATFORM.equals(response.getPeerInfo().getPlatform()),
                    response.hasPeerInfo() ? response.getPeerInfo().getPlatform() : "");
            check("peer_info has one display of 1280x720",
                    response.hasPeerInfo()
                            && response.getPeerInfo().getDisplaysCount() == 1
                            && response.getPeerInfo().getDisplays(0).getWidth() == 1280
                            && response.getPeerInfo().getDisplays(0).getHeight() == 720,
                    response.hasPeerInfo() ? response.getPeerInfo().getDisplaysList().toString() : "");

            boolean loggedIn = waitFor(new Cond() {
                public boolean ok() {
                    return harness.session.isLoggedIn();
                }
            }, 2000, h);
            check("isLoggedIn() becomes true", loggedIn, "");
            check("listener.onLoginOk called once with the viewer id",
                    h.listener.loginOkCount.get() == 1 && "viewer-42".equals(h.listener.loginViewerId),
                    "count=" + h.listener.loginOkCount.get() + " id=" + h.listener.loginViewerId);

            // Two foreign threads call sendMessage while the streaming thread also sends; if the
            // cipher counters were to interleave the frames would not decrypt here.
            final Message delayA = messageWithDelay(1111L);
            final Message delayB = messageWithDelay(2222L);
            final AtomicBoolean sentA = new AtomicBoolean(false);
            final AtomicBoolean sentB = new AtomicBoolean(false);
            final Throwable[] error = new Throwable[1];
            Thread a = new Thread(new Runnable() {
                public void run() {
                    try {
                        sentA.set(harness.session.sendMessage(delayA));
                    } catch (Throwable t) {
                        error[0] = t;
                    }
                }
            }, "test-sender-a");
            Thread b = new Thread(new Runnable() {
                public void run() {
                    try {
                        sentB.set(harness.session.sendMessage(delayB));
                    } catch (Throwable t) {
                        error[0] = t;
                    }
                }
            }, "test-sender-b");
            a.start();
            b.start();
            a.join(3000);
            b.join(3000);
            check("sendMessage() from foreign threads returns true",
                    error[0] == null && sentA.get() && sentB.get(),
                    "a=" + sentA.get() + " b=" + sentB.get() + (error[0] == null ? "" : " error=" + error[0]));

            boolean framesOk = true;
            StringBuilder frameDetail = new StringBuilder();
            int framesSeen = 0;
            int videoMessages = 0;
            Set<Long> delays = new HashSet<Long>();
            for (int i = 0; i < 5; i++) {
                Message message = h.receive();
                if (message.hasVideoFrame()) {
                    videoMessages++;
                    List<EncodedVideoFrame> list = message.getVideoFrame().getH264S().getFramesList();
                    if (list.size() != 1) {
                        framesOk = false;
                        frameDetail.append("frames=").append(list.size()).append(' ');
                        continue;
                    }
                    if (framesSeen >= FRAME_DATA.length) {
                        framesOk = false;
                        frameDetail.append("extra frame ");
                        continue;
                    }
                    EncodedVideoFrame frame = list.get(0);
                    boolean dataOk = Arrays.equals(FRAME_DATA[framesSeen], frame.getData().toByteArray());
                    boolean keyOk = FRAME_KEY[framesSeen] == frame.getKey();
                    boolean ptsOk = FRAME_PTS[framesSeen] == frame.getPts();
                    if (!dataOk || !keyOk || !ptsOk) {
                        framesOk = false;
                        frameDetail.append("frame#").append(framesSeen)
                                .append("[data=").append(dataOk)
                                .append(",key=").append(keyOk)
                                .append(",pts=").append(ptsOk)
                                .append(" pts=").append(frame.getPts())
                                .append("] ");
                    }
                    framesSeen++;
                } else if (message.hasTestDelay()) {
                    TestDelay delay = message.getTestDelay();
                    delays.add(delay.getTime());
                } else {
                    framesOk = false;
                    frameDetail.append("unexpected ").append(message.getUnionCase()).append(' ');
                }
            }
            check("received exactly 3 video_frame(h264s) messages", videoMessages == 3, "got " + videoMessages);
            check("each frame has 1 encoded frame with byte-identical data, key and pts",
                    framesOk && framesSeen == 3, frameDetail.toString());
            check("concurrent sendMessage payloads both arrived intact",
                    delays.contains(1111L) && delays.contains(2222L), String.valueOf(delays));

            h.session.stop();
            boolean closed = h.listener.closed.await(3, TimeUnit.SECONDS);
            check("stop() makes the worker finish and onClosed fire", closed, "");
            check("onClosed exactly once", h.listener.closedCount.get() == 1,
                    "count=" + h.listener.closedCount.get());
            check("closed reason is \"stopped\"", "stopped".equals(h.listener.closedReason),
                    String.valueOf(h.listener.closedReason));
            check("isLoggedIn() false after stop()", !h.session.isLoggedIn(), "");
            check("sendMessage() after stop() returns false",
                    !h.session.sendMessage(messageWithDelay(3333L)), "");
            check("host worker thread has exited", waitFor(new Cond() {
                public boolean ok() {
                    return !hostThreadAlive();
                }
            }, 3000, h), hostThreadNames());
            check("listener saw the expected status milestones",
                    harness.listener.statuses.toString().contains("sending the host's signed id")
                            && harness.listener.statuses.toString().contains("password challenge sent")
                            && harness.listener.statuses.toString().contains("streaming to viewer-42"),
                    harness.listener.statuses.toString());
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
            h = new Harness(HOST_ID, HOST_PASSWORD, null);
            check("sendMessage() before login returns false",
                    !h.session.sendMessage(messageWithDelay(1L)), "");
            h.sendPublicKey(KX_LATEST);
            Hash hash = h.awaitHash();
            LoginResponse response = h.sendLogin(hash, HOST_ID, "definitely-wrong", "viewer-bad");
            check("error == \"Wrong Password\"",
                    response.hasError() && "Wrong Password".equals(response.getError()),
                    "error=\"" + response.getError() + "\"");
            check("failed login carries no peer_info", !response.hasPeerInfo(), "");
            check("session is not logged in", !h.session.isLoggedIn(), "");
            check("onClosed fires after the rejection", h.listener.closed.await(3, TimeUnit.SECONDS), "");
            check("closed reason mentions the rejection",
                    h.listener.closedReason != null && h.listener.closedReason.contains("login rejected"),
                    String.valueOf(h.listener.closedReason));
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
            h = new Harness(HOST_ID, HOST_PASSWORD, null);
            h.sendPublicKey(KX_LATEST);
            Hash hash = h.awaitHash();
            LoginResponse response = h.sendLogin(hash, "999999999", HOST_PASSWORD, "viewer-other");
            check("error == \"Offline\"",
                    response.hasError() && "Offline".equals(response.getError()),
                    "error=\"" + response.getError() + "\"");
            check("session is not logged in", !h.session.isLoggedIn(), "");
            check("onClosed fires after the rejection", h.listener.closed.await(3, TimeUnit.SECONDS), "");
            check("closed reason names the wrong id",
                    h.listener.closedReason != null && h.listener.closedReason.contains("wrong id"),
                    String.valueOf(h.listener.closedReason));
        } catch (Throwable t) {
            fail("case 3 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    /** A viewer that only speaks v0 answers kx_version=0; the responder must match it. */
    private static void testKxVersionZero() throws Exception {
        section("case 4: kx_version=0 handshake split keys");
        Harness h = null;
        try {
            h = new Harness(HOST_ID, HOST_PASSWORD, null);
            final Harness harness = h;
            h.sendPublicKey(0);
            Hash hash = h.awaitHash();
            LoginResponse response = h.sendLogin(hash, HOST_ID, HOST_PASSWORD, "viewer-v0");
            check("login succeeds with kx_version=0",
                    !response.hasError() && response.hasPeerInfo(), response.getError());
            check("streaming state is live (no frames supplied)", waitFor(new Cond() {
                public boolean ok() {
                    return harness.session.isLoggedIn();
                }
            }, 2000, h), "");
        } catch (Throwable t) {
            fail("case 4 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    /** The viewer probes latency with TestDelay before login; the host echoes it verbatim. */
    private static void testPreLoginTestDelayEcho() throws Exception {
        section("case 6: TestDelay sent before login is echoed");
        Harness h = null;
        try {
            h = new Harness(HOST_ID, HOST_PASSWORD, null);
            h.sendPublicKey(KX_LATEST);
            Hash hash = h.awaitHash();
            h.viewerSecure.send(Message.newBuilder()
                    .setTestDelay(TestDelay.newBuilder().setTime(777L).build())
                    .build().toByteArray());
            Message echo = h.receive();
            check("pre-login TestDelay is echoed with the same value",
                    echo.hasTestDelay() && echo.getTestDelay().getTime() == 777L,
                    "got " + echo.getUnionCase());
            LoginResponse response = h.sendLogin(hash, HOST_ID, HOST_PASSWORD, "viewer-delay");
            check("login still succeeds after the echo", !response.hasError(), response.getError());
        } catch (Throwable t) {
            fail("case 6 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    /** After SignedId the host must see public_key first; anything else is a clean rejection. */
    private static void testNonKeyFirstFrameIsRejected() throws Exception {
        section("case 7: first viewer frame is not public_key");
        Harness h = null;
        try {
            h = new Harness(HOST_ID, HOST_PASSWORD, null);
            Message signed = h.awaitSignedId();
            check("host sends signed_id before anything else",
                    signed.hasSignedId(), "got " + signed.getUnionCase());
            h.viewerRaw.send(Message.newBuilder()
                    .setTestDelay(TestDelay.newBuilder().setTime(1L).build())
                    .build().toByteArray());
            check("onClosed fires instead of hanging", h.listener.closed.await(3, TimeUnit.SECONDS), "");
            check("reason is a protocol error",
                    h.listener.closedReason != null && h.listener.closedReason.startsWith("protocol error"),
                    String.valueOf(h.listener.closedReason));
            check("session never logs in", !h.session.isLoggedIn(), "");
        } catch (Throwable t) {
            fail("case 7 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    /** The viewer sends nothing at all; HostSession is blocked in receive() and stop() must free it. */
    private static void testStopUnblocksPendingReceive() throws Exception {
        section("case 5: stop() unblocks a pending receive()");
        Harness h = null;
        try {
            h = new Harness(HOST_ID, HOST_PASSWORD, null);
            Thread.sleep(300); // let the worker reach raw.receive()
            long start = System.currentTimeMillis();
            h.session.stop();
            long stopMs = System.currentTimeMillis() - start;
            check("stop() itself returns promptly", stopMs < 1000, stopMs + " ms");
            boolean closed = h.listener.closed.await(3, TimeUnit.SECONDS);
            check("blocked receive() is released and onClosed fires", closed, "");
            check("closed reason is \"stopped\"", "stopped".equals(h.listener.closedReason),
                    String.valueOf(h.listener.closedReason));
            check("onClosed exactly once", h.listener.closedCount.get() == 1,
                    "count=" + h.listener.closedCount.get());
            check("host worker thread has exited", waitFor(new Cond() {
                public boolean ok() {
                    return !hostThreadAlive();
                }
            }, 3000, h), hostThreadNames());
        } catch (Throwable t) {
            fail("case 5 threw " + t, t);
        } finally {
            if (h != null) {
                h.close();
            }
        }
    }

    // ------------------------------------------------------------- SignedId

    /** Verifies the host's host-first SignedId exactly as a viewer that got pk from hbbs would. */
    private static void checkSignedId(Harness h, String label) throws IOException {
        Message first = h.lastSignedId;
        if (first == null) {
            first = h.awaitSignedId();
        }
        check(label + ": host's first message is signed_id", first.hasSignedId(),
                "got " + first.getUnionCase());
        byte[] blob = first.hasSignedId()
                ? first.getSignedId().getId().toByteArray() : new byte[0];
        check(label + ": signed_id.id is signature(64) || IdPk", blob.length > 64,
                blob.length + " bytes");

        IdPk idPk = null;
        try {
            idPk = IdPk.parseFrom(Ed25519.verify(blob, h.hostSigningPk));
        } catch (Exception e) {
            // left null, reported by the checks below
        }
        check(label + ": SignedId verifies against the host ed25519 public key", idPk != null,
                idPk == null ? "signature mismatch" : "");
        check(label + ": IdPk.id == the host id",
                idPk != null && HOST_ID.equals(idPk.getId()),
                idPk == null ? "(no IdPk)" : idPk.getId());
        check(label + ": IdPk.pk == the host curve25519 box public key",
                idPk != null && Arrays.equals(h.hostPk, idPk.getPk().toByteArray()),
                idPk == null ? "(no IdPk)" : hex4(idPk.getPk().toByteArray()));
        check(label + ": IdPk.kx_version == " + HostSession.HOST_KX_VERSION,
                idPk != null && idPk.getKxVersion() == HostSession.HOST_KX_VERSION,
                idPk == null ? "(no IdPk)" : String.valueOf(idPk.getKxVersion()));
        check(label + ": IdPk.dtls_fingerprint is empty (not a WebRTC transport)",
                idPk != null && idPk.getDtlsFingerprint().isEmpty(),
                idPk == null ? "(no IdPk)" : idPk.getDtlsFingerprint());
    }

    // ------------------------------------------------------------- harness

    /** One loopback connection: HostSession on the host side, a hand-written viewer on the other. */
    private static final class Harness {
        final String hostId;
        final String password;
        final byte[] hostPk;
        final byte[] hostSk;
        final byte[] hostSigningPk;
        final byte[] hostSigningSeed;
        final byte[] viewerPk;
        final byte[] viewerSk;
        final byte[] streamKey = new byte[32];
        final byte[] sealedKey;
        final ServerSocket server;
        final Socket hostSocket;
        final Socket viewerSocket;
        final Transport viewerRaw;
        final RecordingListener listener = new RecordingListener();
        final HostSession session;
        Transport viewerSecure;
        Message lastSignedId;

        Harness(String hostId, String password, HostSession.FrameSource source) throws Exception {
            byte[][] hostKeys = Sodium.genBoxKeyPair();
            hostPk = hostKeys[0];
            hostSk = hostKeys[1];
            byte[][] signingKeys = Ed25519.generateKeyPair();
            hostSigningPk = signingKeys[0];
            hostSigningSeed = signingKeys[1];
            byte[][] viewerKeys = Sodium.genBoxKeyPair();
            viewerPk = viewerKeys[0];
            viewerSk = viewerKeys[1];
            new SecureRandom().nextBytes(streamKey);
            sealedKey = Sodium.boxSeal(streamKey, new byte[Sodium.NONCE_BYTES], hostPk, viewerSk);

            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            viewerSocket = new Socket();
            viewerSocket.connect(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.getLocalPort()), 3000);
            viewerSocket.setSoTimeout(5000);
            viewerSocket.setTcpNoDelay(true);
            hostSocket = server.accept();
            hostSocket.setTcpNoDelay(true);

            this.hostId = hostId;
            this.password = password;
            session = new HostSession(new StreamTransport(hostSocket), hostId, password,
                    hostPk, hostSk, hostSigningSeed, listener, source);
            session.setDisplaySize(1280, 720);
            viewerRaw = new StreamTransport(viewerSocket);
            session.start();
        }

        /** Reads the host's first frame, which must be its SignedId. */
        Message awaitSignedId() throws IOException {
            byte[] frame = viewerRaw.receive();
            if (frame == null) {
                throw new EOFException("viewer: host closed before sending SignedId");
            }
            lastSignedId = Message.parseFrom(frame);
            return lastSignedId;
        }

        /** Answers with public_key, the way PeerHandshake does after verifying SignedId. */
        void sendPublicKey(int kxVersion) throws IOException {
            awaitSignedId();
            PublicKey publicKey = PublicKey.newBuilder()
                    .setAsymmetricValue(ByteString.copyFrom(viewerPk))
                    .setSymmetricValue(ByteString.copyFrom(sealedKey))
                    .setKxVersion(kxVersion)
                    .build();
            viewerRaw.send(Message.newBuilder().setPublicKey(publicKey).build().toByteArray());
            viewerSecure = new SecureTransport(viewerRaw, new EncryptCipher(
                    Encrypt.newSplit(streamKey, true, viewerPk, hostPk,
                            kxVersion, Encrypt.kxVersionFor(kxVersion))));
        }

        Message receive() throws IOException {
            byte[] frame = viewerSecure.receive();
            if (frame == null) {
                throw new EOFException("viewer: host closed the stream");
            }
            return Message.parseFrom(frame);
        }

        Hash awaitHash() throws IOException {
            Message message = receive();
            if (!message.hasHash()) {
                throw new IOException("expected Hash, got " + message.getUnionCase());
            }
            return message.getHash();
        }

        LoginResponse sendLogin(Hash hash, String username, String plainPassword, String viewerId)
                throws IOException {
            byte[] digest = passwordDigest(plainPassword, hash.getSalt(), hash.getChallenge());
            LoginRequest request = LoginRequest.newBuilder()
                    .setUsername(username)
                    .setPassword(ByteString.copyFrom(digest))
                    .setMyId(viewerId)
                    .setMyName("loopback-viewer")
                    .setMyPlatform("Linux")
                    .setVersion("1.5.1")
                    .setSessionId(4242L)
                    .build();
            viewerSecure.send(Message.newBuilder().setLoginRequest(request).build().toByteArray());
            Message message = receive();
            if (!message.hasLoginResponse()) {
                throw new IOException("expected LoginResponse, got " + message.getUnionCase());
            }
            return message.getLoginResponse();
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

    private static final class FakeFrames implements HostSession.FrameSource {
        private final byte[][] data;
        private final boolean[] key;
        private final long[] pts;
        private int index;

        FakeFrames(byte[][] data, boolean[] key, long[] pts) {
            this.data = data;
            this.key = key;
            this.pts = pts;
        }

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

    private interface Cond {
        boolean ok();
    }

    private static boolean waitFor(Cond cond, long millis, Harness h) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.ok()) {
                return true;
            }
            Thread.sleep(5);
        }
        return cond.ok();
    }

    private static Message messageWithDelay(long value) {
        return Message.newBuilder()
                .setTestDelay(TestDelay.newBuilder().setTime(value).build())
                .build();
    }

    private static byte[] passwordDigest(String password, String salt, String challenge) {
        byte[] first = sha256(concat(password.getBytes(UTF8), salt.getBytes(UTF8)));
        return sha256(concat(first, challenge.getBytes(UTF8)));
    }

    private static byte[] sha256(byte[] data) throws IllegalStateException {
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

    private static boolean isHex(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            if (!digit && !lower) {
                return false;
            }
        }
        return !text.isEmpty();
    }

    private static String hex4(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, b.length); i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }

    private static boolean hostThreadAlive() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("rustdesk-host-session".equals(thread.getName()) && thread.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static String hostThreadNames() {
        StringBuilder sb = new StringBuilder();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("rustdesk-host-session")) {
                sb.append(thread.getName()).append("[").append(thread.getState()).append("] ");
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
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
            System.out.println("[FAIL] " + name + (detail == null || detail.isEmpty() ? "" : " -- " + detail));
        }
    }

    private static void fail(String name, Throwable t) {
        checks++;
        failures++;
        System.out.println("[FAIL] " + name);
        t.printStackTrace(System.out);
    }
}
