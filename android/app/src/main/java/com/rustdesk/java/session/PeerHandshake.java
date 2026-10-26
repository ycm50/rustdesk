package com.rustdesk.java.session;

import com.google.protobuf.ByteString;
import com.rustdesk.java.crypto.Encrypt;
import com.rustdesk.java.crypto.Sodium;
import com.rustdesk.java.net.EncryptCipher;
import com.rustdesk.java.net.SecureTransport;
import com.rustdesk.java.net.Transport;

import java.io.IOException;
import java.security.SecureRandom;

import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.PublicKey;

/**
 * Key exchange with a peer, mirroring src/client.rs:secure_connection.
 *
 * <p>The peer speaks first: it sends {@code SignedId}, a blob the rendezvous server signed. We
 * verify it against the deployment's ed25519 key, then seal a fresh 32-byte stream key to the
 * peer's curve25519 key and answer with {@code PublicKey}. Everything after that is encrypted.
 */
public final class PeerHandshake {

    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Security {
        /** The peer proved its identity; traffic is encrypted. */
        SECURED,
        /** The peer sent no identity blob, so the stream stays plaintext like the Rust fallback. */
        UNSECURED
    }

    public static final class Result {
        public final Transport transport;
        public final Security security;
        public final PeerIdentity identity;
        /** First message we did not consume, so the caller can process it (a direct peer often
         *  opens with the password challenge instead of a signed id). Null when not applicable. */
        public final Message pending;

        Result(Transport transport, Security security, PeerIdentity identity) {
            this(transport, security, identity, null);
        }

        Result(Transport transport, Security security, PeerIdentity identity, Message pending) {
            this.transport = transport;
            this.security = security;
            this.identity = identity;
            this.pending = pending;
        }
    }

    private PeerHandshake() {}

    public static Result connect(Transport raw, String expectedPeerId, byte[] rsPublicKey)
            throws IOException {
        return connect(raw, expectedPeerId, rsPublicKey, null);
    }

    /**
     * @param peerSigningKey the peer's ed25519 key, already verified by the rendezvous server
     *     ({@code PunchHoleResponse.pk}); the peer's own SignedId is signed with it. When null we
     *     fall back to verifying the SignedId with the deployment key, which is what older peers
     *     that let the server sign their session id do.
     */
    public static Result connect(Transport raw, String expectedPeerId, byte[] rsPublicKey,
                                 byte[] peerSigningKey) throws IOException {
        // The host sends its SignedId first, but on a direct connection a periodic TestDelay can
        // land in front of it, so scan a short window instead of judging the very first frame.
        // Treating that TestDelay as "the peer has no identity" put the two sides out of sync:
        // the host waited for a LoginRequest while we waited for a Hash, and the screen stayed
        // blank with zero frames.
        Message message = null;
        Message pending = null;
        long deadline = System.currentTimeMillis() + 5_000L;
        while (message == null) {
            byte[] frame = raw.receive();
            if (frame == null) {
                throw new IOException("peer closed before the key exchange");
            }
            Message candidate;
            try {
                candidate = Message.parseFrom(frame);
            } catch (Exception e) {
                throw new IOException("first frame is not a message", e);
            }
            if (candidate.hasSignedId() && !candidate.getSignedId().getId().isEmpty()) {
                message = candidate;
                break;
            }
            if (candidate.hasTestDelay()) {
                // Answer it so the host's latency estimate stays alive, then keep scanning.
                raw.send(Message.newBuilder().setTestDelay(candidate.getTestDelay())
                        .build().toByteArray());
                if (System.currentTimeMillis() >= deadline) {
                    pending = candidate;
                    break;
                }
                continue;
            }
            // Some other leading message: keep it so the caller can act on it (a direct peer often
            // leads with the password challenge).
            pending = candidate;
            break;
        }

        if (message == null) {
            // No signed id. If the peer already sent something else (a direct connection often
            // opens with the password challenge), hand that frame back instead of spending it on an
            // empty reply, otherwise both sides end up waiting for each other.
            if (pending == null) {
                raw.send(Message.getDefaultInstance().toByteArray());
            }
            return new Result(raw, Security.UNSECURED, null, pending);
        }

        byte[] signer = peerSigningKey != null ? peerSigningKey : rsPublicKey;
        PeerIdentity identity;
        try {
            // With neither key there is no server in the picture (direct IP mode), so the blob can
            // only be read, not authenticated.
            identity = signer == null
                    ? PeerIdentity.parseUnverified(message.getSignedId().getId().toByteArray())
                    : PeerIdentity.verify(
                    message.getSignedId().getId().toByteArray(), signer);
        } catch (IllegalArgumentException e) {
            throw new IOException("peer identity rejected: " + e.getMessage(), e);
        }
        if (!identity.matches(expectedPeerId)) {
            throw new IOException("peer identity mismatch: expected " + expectedPeerId
                    + " but the blob names " + identity.id);
        }

        byte[][] keyPair = Sodium.genBoxKeyPair();
        byte[] ourPk = keyPair[0];
        byte[] ourSk = keyPair[1];

        byte[] streamKey = new byte[Encrypt.KEY_BYTES];
        RANDOM.nextBytes(streamKey);

        byte[] sealedKey = Sodium.boxSeal(streamKey, new byte[Sodium.NONCE_BYTES],
                identity.publicKey, ourSk);
        int picked = Encrypt.kxVersionFor(identity.kxVersion);

        PublicKey reply = PublicKey.newBuilder()
                .setAsymmetricValue(ByteString.copyFrom(ourPk))
                .setSymmetricValue(ByteString.copyFrom(sealedKey))
                .setKxVersion(picked)
                .build();
        raw.send(Message.newBuilder().setPublicKey(reply).build().toByteArray());

        Encrypt encrypt = Encrypt.newSplit(streamKey, true, ourPk, identity.publicKey,
                identity.kxVersion, picked);
        return new Result(new SecureTransport(raw, new EncryptCipher(encrypt)),
                Security.SECURED, identity);
    }
}






