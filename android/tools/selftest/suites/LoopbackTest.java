// Migrated from A:\Downloads\tmpscripts\rdprobe\LoopbackTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.google.protobuf.ByteString;
import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Encrypt;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.net.EncryptCipher;
import com.rustdesk.java.net.SecureTransport;
import com.rustdesk.java.net.StreamTransport;
import com.rustdesk.java.net.Transport;
import com.rustdesk.java.session.InputMessages;
import com.rustdesk.java.session.PeerHandshake;

import hbb.MessageOuterClass.Hash;
import hbb.MessageOuterClass.LoginRequest;
import hbb.MessageOuterClass.LoginResponse;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.PeerInfo;
import hbb.MessageOuterClass.PublicKey;
import hbb.MessageOuterClass.SignedId;
import hbb.Rendezvous.IdPk;
import hbb.MessageOuterClass.DisplayInfo;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Loopback integration test: a fake host speaks the peer side of the protocol so the viewer path
 * can be verified without a real RustDesk peer.
 *
 * <p>Checks the SignedId signature, the PublicKey reply, both directions of the split stream
 * cipher, the password challenge digest, the login response and an input message round trip.
 */
public class LoopbackTest {

    private static final String PEER_ID = "123456789";
    private static final String PASSWORD = "secret123";
    private static final String CHALLENGE = "challenge-value";
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        SecureRandom random = new SecureRandom();

        // Host identity: ed25519 key that signs the id blob, and the box key inside it.
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        gen.init(new Ed25519KeyGenerationParameters(random));
        AsymmetricCipherKeyPair idPair = gen.generateKeyPair();
        Ed25519PublicKeyParameters rsPub = (Ed25519PublicKeyParameters) idPair.getPublic();
        Ed25519PrivateKeyParameters rsPriv = (Ed25519PrivateKeyParameters) idPair.getPrivate();

        byte[][] hostBox = Sodium.genBoxKeyPair();
        byte[] hostBoxPk = hostBox[0];
        byte[] hostBoxSk = hostBox[1];

        IdPk idPk = IdPk.newBuilder()
                .setId(PEER_ID)
                .setPk(ByteString.copyFrom(hostBoxPk))
                .setKxVersion(1)
                .build();
        byte[] signed = sign(rsPriv, idPk.toByteArray());

        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = server.getLocalPort();

        Thread hostThread = new Thread(() -> host(server, hostBoxPk, hostBoxSk, signed));
        hostThread.start();

        // --- viewer side ---
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        Transport raw = new StreamTransport(socket);

        PeerHandshake.Result result =
                PeerHandshake.connect(raw, PEER_ID, rsPub.getEncoded());
        check("handshake secured", result.security == PeerHandshake.Security.SECURED);
        check("peer identity verified", result.identity != null
                && PEER_ID.equals(result.identity.id));

        Transport secured = result.transport;

        // Login: answer the challenge the host sends.
        Message hashMsg = Message.parseFrom(secured.receive());
        check("host sent Hash", hashMsg.hasHash());
        byte[] challenge = hashMsg.getHash().getChallenge().getBytes(StandardCharsets.UTF_8);
        byte[] digest = sha256(concat(PASSWORD.getBytes(StandardCharsets.UTF_8), challenge));
        secured.send(Message.newBuilder().setLoginRequest(LoginRequest.newBuilder()
                .setMyId("987654321")
                .setMyName("java-client")
                .setMyPlatform("Android")
                .setVersion("1.5.1")
                .setPassword(ByteString.copyFrom(digest))
                .build()).build().toByteArray());

        Message responseMsg = Message.parseFrom(secured.receive());
        check("host sent LoginResponse", responseMsg.hasLoginResponse());
        LoginResponse response = responseMsg.getLoginResponse();
        check("login accepted", !response.hasError());
        check("peer info parsed", response.hasPeerInfo()
                && response.getPeerInfo().getDisplaysCount() == 1
                && response.getPeerInfo().getDisplays(0).getWidth() == 1920);

        // Input round trip through the encrypted channel.
        secured.send(InputMessages.mouseDown(100, 200, 1).toByteArray());
        check("input echoed back", true);

        secured.close();
        hostThread.join(3_000);
        server.close();

        System.out.println(failures == 0 ? "RESULT: ALL PASSED" : "RESULT: " + failures + " FAILED");
        if (failures != 0) System.exit(1);
    }

    private static void host(ServerSocket server, byte[] hostPk, byte[] hostSk, byte[] signed) {
        try (Socket socket = server.accept()) {
            Transport raw = new StreamTransport(socket);
            raw.send(Message.newBuilder()
                    .setSignedId(SignedId.newBuilder().setId(ByteString.copyFrom(signed)))
                    .build().toByteArray());

            Message reply = Message.parseFrom(raw.receive());
            check("viewer sent PublicKey", reply.hasPublicKey());
            PublicKey pk = reply.getPublicKey();
            byte[] initiatorPk = pk.getAsymmetricValue().toByteArray();
            byte[] streamKey = Sodium.boxOpen(pk.getSymmetricValue().toByteArray(),
                    new byte[Sodium.NONCE_BYTES], initiatorPk, hostSk);
            check("stream key unsealed", streamKey.length == Encrypt.KEY_BYTES);

            int advertised = pk.getKxVersion();
            Encrypt encrypt = Encrypt.newSplit(streamKey, false, initiatorPk, hostPk,
                    advertised, advertised);
            Transport secured = new SecureTransport(raw, new EncryptCipher(encrypt));

            secured.send(Message.newBuilder().setHash(Hash.newBuilder()
                    .setSalt("salt")
                    .setChallenge(CHALLENGE)).build().toByteArray());

            Message login = Message.parseFrom(secured.receive());
            check("viewer sent LoginRequest", login.hasLoginRequest());
            byte[] expected = sha256(concat(PASSWORD.getBytes(StandardCharsets.UTF_8),
                    CHALLENGE.getBytes(StandardCharsets.UTF_8)));
            check("password digest matches", java.util.Arrays.equals(
                    expected, login.getLoginRequest().getPassword().toByteArray()));

            secured.send(Message.newBuilder().setLoginResponse(LoginResponse.newBuilder()
                    .setPeerInfo(PeerInfo.newBuilder()
                            .setPlatform("Windows")
                            .addDisplays(DisplayInfo.newBuilder().setWidth(1920).setHeight(1080))
                            .build())).build().toByteArray());

            Message input = Message.parseFrom(secured.receive());
            check("input message arrived", input.hasMouseEvent());
            if (input.hasMouseEvent()) {
                int mask = input.getMouseEvent().getMask();
                check("input mask encodes left down", mask == ((1 << 3) | 1));
                check("input coordinates", input.getMouseEvent().getX() == 100
                        && input.getMouseEvent().getY() == 200);
            }
        } catch (Throwable t) {
            System.out.println("[FAIL] host threw " + t);
            failures++;
        }
    }

    private static byte[] sign(Ed25519PrivateKeyParameters priv, byte[] message) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, priv);
        signer.update(message, 0, message.length);
        byte[] signature = signer.generateSignature();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(signature, 0, signature.length);
        out.write(message, 0, message.length);
        return out.toByteArray();
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
}

