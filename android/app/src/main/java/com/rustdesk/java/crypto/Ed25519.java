package com.rustdesk.java.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * libsodium-style detached ed25519 signing and verification.
 *
 * <p>The key format matches libsodium's {@code crypto_sign_ed25519_seed_keypair}: a 32-byte seed
 * derives both the 32-byte public key and the 64-byte expanded secret key. Only the seed is ever
 * handled here, since that is what RustDesk stores in {@code Config::get_key_pair()['sk']}.</p>
 *
 * <p>Signatures are 64 bytes. {@link #signed(byte[], byte[])} and {@link #verify(byte[], byte[])}
 * speak the {@code signature || message} layout that libsodium's {@code crypto_sign} produces and
 * that RustDesk puts on the wire in {@code Message.signed_id.id}.</p>
 */
public final class Ed25519 {

    public static final int SIGNATURE_BYTES = 64;
    /** Seed length, also the secret key length libsodium reports for ed25519. */
    public static final int SEED_BYTES = 32;
    public static final int PUBLIC_KEY_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ed25519() {}

    /**
     * Signs {@code message} with the ed25519 key derived from {@code privateKeySeed32}.
     *
     * @param message          data to sign, may be empty
     * @param privateKeySeed32 32-byte seed, libsodium's {@code crypto_sign_SECRETKEYBYTES} seed form
     * @return the 64-byte detached signature
     */
    public static byte[] sign(byte[] message, byte[] privateKeySeed32) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey(privateKeySeed32));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    /**
     * Creates a fresh key pair.
     *
     * @return {@code {publicKey(32), privateKeySeed(32)}}
     */
    public static byte[][] generateKeyPair() {
        byte[] seed = new byte[SEED_BYTES];
        RANDOM.nextBytes(seed);
        return new byte[][] { publicKeyOf(seed), seed };
    }

    /**
     * @param privateKeySeed32 32-byte seed
     * @return the 32-byte ed25519 public key derived from that seed
     */
    public static byte[] publicKeyOf(byte[] privateKeySeed32) {
        return privateKey(privateKeySeed32).generatePublicKey().getEncoded();
    }

    /**
     * @return {@code signature(64) || message}, the layout {@code Message.signed_id.id} carries
     */
    public static byte[] signed(byte[] message, byte[] privateKeySeed32) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        byte[] signature = sign(message, privateKeySeed32);
        byte[] out = new byte[SIGNATURE_BYTES + message.length];
        System.arraycopy(signature, 0, out, 0, SIGNATURE_BYTES);
        System.arraycopy(message, 0, out, SIGNATURE_BYTES, message.length);
        return out;
    }

    /**
     * @param signed signature(64) followed by the message, as libsodium's crypto_sign_open takes it
     * @return the message that was signed
     * @throws IllegalArgumentException when the signature does not verify
     */
    public static byte[] verify(byte[] signed, byte[] publicKey) {
        if (signed == null || signed.length <= SIGNATURE_BYTES) {
            throw new IllegalArgumentException("signed blob too short");
        }
        if (publicKey == null || publicKey.length != PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("ed25519 public key must be 32 bytes");
        }
        byte[] signature = Arrays.copyOfRange(signed, 0, SIGNATURE_BYTES);
        byte[] message = Arrays.copyOfRange(signed, SIGNATURE_BYTES, signed.length);

        // BouncyCastle verifies a detached signature: the message goes through update() and
        // verifySignature() takes exactly the 64 signature bytes.
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        verifier.update(message, 0, message.length);
        if (!verifier.verifySignature(signature)) {
            throw new IllegalArgumentException("ed25519 signature mismatch");
        }
        return message;
    }

    private static Ed25519PrivateKeyParameters privateKey(byte[] seed) {
        if (seed == null) {
            throw new NullPointerException("private key seed");
        }
        if (seed.length != SEED_BYTES) {
            throw new IllegalArgumentException("ed25519 private key seed must be "
                    + SEED_BYTES + " bytes");
        }
        return new Ed25519PrivateKeyParameters(seed, 0);
    }
}
