package com.rustdesk.java.net;

import com.google.protobuf.ByteString;
import com.rustdesk.java.crypto.Ed25519;
import com.rustdesk.java.crypto.Encrypt;
import com.rustdesk.java.crypto.Sodium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import hbb.Rendezvous.KeyExchange;
import hbb.Rendezvous.KxParams;
import hbb.Rendezvous.RendezvousMessage;

/**
 * The rendezvous server's own key exchange, mirroring src/common.rs:key_exchange.
 *
 * <p>hbbs opens with {@code KeyExchange} carrying an ed25519-signed X25519 public key. We verify it
 * against the deployment key, seal a fresh stream key to it and answer with
 * {@code KeyExchange{[ourPk, sealedKey], picked}}. Everything after that (RegisterPeer,
 * PunchHoleRequest, …) travels encrypted; the server drops plaintext messages otherwise.
 */
public final class RendezvousKeyExchange {

    private static final byte[] PARAMS_DOMAIN = "rdkx-params".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private RendezvousKeyExchange() {}

    /** Returns the encrypted stream, or the original transport if the server does not offer one. */
    public static Transport secure(Transport raw, byte[] rsPublicKey) throws IOException {
        byte[] frame = raw.receive();
        if (frame == null) {
            throw new IOException("server closed before the key exchange");
        }
        RendezvousMessage message;
        try {
            message = RendezvousMessage.parseFrom(frame);
        } catch (Exception e) {
            throw new IOException("first frame is not a rendezvous message", e);
        }
        if (!message.hasKeyExchange()) {
            // Older servers start straight with the protocol; keep the plaintext stream.
            return raw;
        }

        KeyExchange exchange = message.getKeyExchange();
        if (exchange.getKeysCount() != 1) {
            throw new IOException("key exchange must carry exactly one key");
        }

        byte[] serverPk;
        try {
            serverPk = Ed25519.verify(exchange.getKeys(0).toByteArray(), rsPublicKey);
        } catch (IllegalArgumentException e) {
            throw new IOException("server public key signature rejected", e);
        }
        if (serverPk.length != 32) {
            throw new IOException("server public key has the wrong length");
        }

        boolean signsParams = (serverPk[31] & 0x80) != 0 || !exchange.getSignedParams().isEmpty();
        if (signsParams) {
            try {
                byte[] signed = Ed25519.verify(exchange.getSignedParams().toByteArray(), rsPublicKey);
                String domain = new String(PARAMS_DOMAIN, StandardCharsets.US_ASCII);
                String body = new String(signed, StandardCharsets.ISO_8859_1);
                if (!body.startsWith(domain)) {
                    throw new IllegalArgumentException("missing kx params domain prefix");
                }
                byte[] paramsBytes = new byte[signed.length - PARAMS_DOMAIN.length];
                System.arraycopy(signed, PARAMS_DOMAIN.length, paramsBytes, 0, paramsBytes.length);
                KxParams params = KxParams.parseFrom(paramsBytes);
                if (!java.util.Arrays.equals(params.getPk().toByteArray(), serverPk)
                        || params.getVersion() != exchange.getVersion()) {
                    throw new IllegalArgumentException("kx params disagree with the exchange");
                }
            } catch (IllegalArgumentException | com.google.protobuf.InvalidProtocolBufferException e) {
                throw new IOException("signed key exchange parameters rejected", e);
            }
        }

        byte[][] keyPair = Sodium.genBoxKeyPair();
        byte[] ourPk = keyPair[0];
        byte[] ourSk = keyPair[1];

        byte[] streamKey = new byte[Encrypt.KEY_BYTES];
        RANDOM.nextBytes(streamKey);
        byte[] sealedKey = Sodium.boxSeal(streamKey, new byte[Sodium.NONCE_BYTES], serverPk, ourSk);

        int picked = Encrypt.kxVersionFor(exchange.getVersion());
        raw.send(RendezvousMessage.newBuilder()
                .setKeyExchange(KeyExchange.newBuilder()
                        .addKeys(ByteString.copyFrom(ourPk))
                        .addKeys(ByteString.copyFrom(sealedKey))
                        .setVersion(picked))
                .build().toByteArray());

        Encrypt encrypt = Encrypt.newSplit(streamKey, true, ourPk, serverPk,
                exchange.getVersion(), picked);
        return new SecureTransport(raw, new EncryptCipher(encrypt));
    }
}
