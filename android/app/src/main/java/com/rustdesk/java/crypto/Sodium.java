package com.rustdesk.java.crypto;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.engines.XSalsa20Engine;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.math.ec.rfc7748.X25519;

/**
 * libsodium-compatible primitives implemented with BouncyCastle only.
 *
 * <p>Supported operations:</p>
 * <ul>
 *   <li>{@code crypto_box} (X25519 + HSalsa20 beforenm + XSalsa20-Poly1305)</li>
 *   <li>{@code crypto_secretbox} (XSalsa20-Poly1305, tag in front)</li>
 *   <li>{@code crypto_generichash} (keyed or unkeyed BLAKE2b)</li>
 * </ul>
 *
 * <p>No Android classes and no JNI are used; this runs on a desktop JDK too.</p>
 *
 * <p>Wire format of a sealed message is {@code tag(16) || ciphertext}, matching
 * libsodium's {@code crypto_secretbox_easy} / {@code crypto_box_easy}. The
 * Poly1305 key is the first 32 bytes of the XSalsa20 block-0 keystream, and the
 * message is encrypted with the keystream starting at byte offset 32, exactly
 * like libsodium's detached secretbox implementation.</p>
 */
public final class Sodium {

    public static final int PUBLIC_KEY_BYTES = 32;
    public static final int SECRET_KEY_BYTES = 32;
    public static final int SECRETBOX_KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;
    public static final int MAC_BYTES = 16;

    private static final int BLAKE2B_MAX_BYTES = 64;
    private static final int BLAKE2B_MAX_KEY_BYTES = 64;

    private static final int[] SIGMA = {
        0x61707865, 0x3320646e, 0x79622d32, 0x6b206574
    };

    private static final byte[] ZERO_16 = new byte[16];

    private static final SecureRandom RANDOM = new SecureRandom();

    private Sodium() {
    }

    /** Exception thrown when an authenticated message fails to open. */
    public static class SodiumException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SodiumException(String message) {
            super(message);
        }
    }

    /** Exception thrown when a Poly1305 tag does not verify. */
    public static final class DecryptionException extends SodiumException {
        private static final long serialVersionUID = 1L;

        public DecryptionException(String message) {
            super(message);
        }
    }

    /**
     * Generates a Curve25519 box key pair.
     *
     * @return a two-element array: {@code [0] = public key (32 bytes)},
     *         {@code [1] = secret key (32 bytes)}, the same order as
     *         {@code crypto_box_keypair(pk, sk)}
     */
    public static byte[][] genBoxKeyPair() {
        byte[] secretKey = new byte[SECRET_KEY_BYTES];
        RANDOM.nextBytes(secretKey);
        byte[] publicKey = boxPublicKey(secretKey);
        return new byte[][] { publicKey, secretKey };
    }

    /**
     * Derives the Curve25519 public key of a secret key.
     *
     * @param secretKey 32-byte secret key
     * @return 32-byte public key
     */
    public static byte[] boxPublicKey(byte[] secretKey) {
        checkLength(secretKey, SECRET_KEY_BYTES, "secret key");
        byte[] publicKey = new byte[PUBLIC_KEY_BYTES];
        X25519.scalarMultBase(secretKey, 0, publicKey, 0);
        return publicKey;
    }

    /**
     * Computes the precomputed shared key of {@code crypto_box_beforenm}:
     * X25519(ownSk, peerPk), then HSalsa20(shared, 16 zero bytes).
     *
     * @param peerPublicKey 32-byte peer public key
     * @param ownSecretKey 32-byte own secret key
     * @return 32-byte key suitable for {@link #secretboxSeal}
     */
    public static byte[] boxBeforenm(byte[] peerPublicKey, byte[] ownSecretKey) {
        checkLength(peerPublicKey, PUBLIC_KEY_BYTES, "peer public key");
        checkLength(ownSecretKey, SECRET_KEY_BYTES, "own secret key");
        byte[] shared = new byte[32];
        boolean ok = X25519.calculateAgreement(ownSecretKey, 0, peerPublicKey, 0, shared, 0);
        if (!ok) {
            Arrays.fill(shared, (byte) 0);
            throw new IllegalArgumentException("invalid Curve25519 public key");
        }
        try {
            return hsalsa20(shared, ZERO_16);
        } finally {
            Arrays.fill(shared, (byte) 0);
        }
    }

    /**
     * {@code crypto_box_easy} with a detached nonce. Output is tag || ciphertext.
     */
    public static byte[] boxSeal(byte[] message, byte[] nonce24, byte[] peerPublicKey,
            byte[] ownSecretKey) {
        byte[] key = boxBeforenm(peerPublicKey, ownSecretKey);
        try {
            return secretboxSeal(message, nonce24, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * {@code crypto_box_open_easy} with a detached nonce. Input is tag || ciphertext.
     *
     * @throws DecryptionException if the tag does not verify
     */
    public static byte[] boxOpen(byte[] sealed, byte[] nonce24, byte[] peerPublicKey,
            byte[] ownSecretKey) {
        byte[] key = boxBeforenm(peerPublicKey, ownSecretKey);
        try {
            return secretboxOpen(sealed, nonce24, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * {@code crypto_secretbox_easy}: XSalsa20-Poly1305. Output is tag(16) || ciphertext.
     *
     * @param message plaintext
     * @param nonce24 24-byte nonce
     * @param key32   32-byte secret key
     * @return sealed message, {@code 16 + message.length} bytes
     */
    public static byte[] secretboxSeal(byte[] message, byte[] nonce24, byte[] key32) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        checkLength(nonce24, NONCE_BYTES, "nonce");
        checkLength(key32, SECRETBOX_KEY_BYTES, "secretbox key");

        int messageLength = message.length;
        byte[] keystream = xsalsa20Keystream(key32, nonce24, 32 + messageLength);
        byte[] ciphertext = new byte[messageLength];
        for (int i = 0; i < messageLength; i++) {
            ciphertext[i] = (byte) (message[i] ^ keystream[32 + i]);
        }

        byte[] tag = new byte[MAC_BYTES];
        poly1305(tag, keystream, 0, ciphertext, 0, messageLength);

        byte[] sealed = new byte[MAC_BYTES + messageLength];
        System.arraycopy(tag, 0, sealed, 0, MAC_BYTES);
        System.arraycopy(ciphertext, 0, sealed, MAC_BYTES, messageLength);

        Arrays.fill(keystream, (byte) 0);
        Arrays.fill(ciphertext, (byte) 0);
        return sealed;
    }

    /**
     * {@code crypto_secretbox_open_easy}. Input is tag(16) || ciphertext.
     *
     * @throws DecryptionException if the message is shorter than the tag or the tag is wrong
     */
    public static byte[] secretboxOpen(byte[] sealed, byte[] nonce24, byte[] key32) {
        if (sealed == null) {
            throw new NullPointerException("sealed");
        }
        checkLength(nonce24, NONCE_BYTES, "nonce");
        checkLength(key32, SECRETBOX_KEY_BYTES, "secretbox key");
        if (sealed.length < MAC_BYTES) {
            throw new DecryptionException("sealed message is shorter than the MAC");
        }

        int ciphertextLength = sealed.length - MAC_BYTES;
        byte[] keystream = xsalsa20Keystream(key32, nonce24, 32 + ciphertextLength);
        byte[] expectedTag = new byte[MAC_BYTES];
        poly1305(expectedTag, keystream, 0, sealed, MAC_BYTES, ciphertextLength);

        byte[] tag = new byte[MAC_BYTES];
        System.arraycopy(sealed, 0, tag, 0, MAC_BYTES);
        boolean valid = MessageDigest.isEqual(tag, expectedTag);
        if (!valid) {
            Arrays.fill(keystream, (byte) 0);
            throw new DecryptionException("Poly1305 authentication failed");
        }

        byte[] plaintext = new byte[ciphertextLength];
        for (int i = 0; i < ciphertextLength; i++) {
            plaintext[i] = (byte) (sealed[MAC_BYTES + i] ^ keystream[32 + i]);
        }
        Arrays.fill(keystream, (byte) 0);
        return plaintext;
    }

    /**
     * {@code crypto_generichash}: BLAKE2b with a selectable output size and optional key.
     *
     * @param outLength  digest length in bytes, 1..64
     * @param keyOrNull  key of 1..64 bytes, or null/empty for unkeyed BLAKE2b
     * @param parts      message parts, hashed in order; null entries are skipped
     * @return digest of {@code outLength} bytes
     */
    public static byte[] blake2b(int outLength, byte[] keyOrNull, byte[]... parts) {
        if (outLength < 1 || outLength > BLAKE2B_MAX_BYTES) {
            throw new IllegalArgumentException("BLAKE2b output length must be 1..64 bytes");
        }
        int keyLength = keyOrNull == null ? 0 : keyOrNull.length;
        if (keyLength > BLAKE2B_MAX_KEY_BYTES) {
            throw new IllegalArgumentException("BLAKE2b key must be at most 64 bytes");
        }

        // BouncyCastle quirk: the unkeyed constructor takes the digest size in BITS
        // while the keyed (key, digestLength, salt, personalization) constructor takes
        // it in BYTES.
        Blake2bDigest digest = keyLength == 0
                ? new Blake2bDigest(outLength * 8)
                : new Blake2bDigest(keyOrNull, outLength, null, null);

        if (parts != null) {
            for (byte[] part : parts) {
                if (part != null && part.length > 0) {
                    digest.update(part, 0, part.length);
                }
            }
        }

        byte[] out = new byte[outLength];
        digest.doFinal(out, 0);
        return out;
    }

    /** BLAKE2b-256 convenience wrapper. */
    public static byte[] blake2b256(byte[] keyOrNull, byte[]... parts) {
        return blake2b(32, keyOrNull, parts);
    }

    /**
     * HSalsa20 as used by libsodium's {@code crypto_core_hsalsa20}. The core is
     * implemented locally because BouncyCastle does not expose HSalsa20 on its own.
     *
     * @param key32 input key
     * @param in16  16-byte input
     * @return 32-byte HSalsa20 output
     */
    static byte[] hsalsa20(byte[] key32, byte[] in16) {
        checkLength(key32, 32, "HSalsa20 key");
        checkLength(in16, 16, "HSalsa20 input");

        int[] x = new int[16];
        x[0] = SIGMA[0];
        x[5] = SIGMA[1];
        x[10] = SIGMA[2];
        x[15] = SIGMA[3];
        for (int i = 0; i < 4; i++) {
            x[1 + i] = load32LE(key32, i * 4);
            x[11 + i] = load32LE(key32, 16 + i * 4);
            x[6 + i] = load32LE(in16, i * 4);
        }

        for (int i = 0; i < 10; i++) {
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 5, 9, 13, 1);
            quarterRound(x, 10, 14, 2, 6);
            quarterRound(x, 15, 3, 7, 11);
            quarterRound(x, 0, 1, 2, 3);
            quarterRound(x, 5, 6, 7, 4);
            quarterRound(x, 10, 11, 8, 9);
            quarterRound(x, 15, 12, 13, 14);
        }

        byte[] out = new byte[32];
        store32LE(out, 0, x[0]);
        store32LE(out, 4, x[5]);
        store32LE(out, 8, x[10]);
        store32LE(out, 12, x[15]);
        store32LE(out, 16, x[6]);
        store32LE(out, 20, x[7]);
        store32LE(out, 24, x[8]);
        store32LE(out, 28, x[9]);
        return out;
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[b] ^= rotateLeft(x[a] + x[d], 7);
        x[c] ^= rotateLeft(x[b] + x[a], 9);
        x[d] ^= rotateLeft(x[c] + x[b], 13);
        x[a] ^= rotateLeft(x[d] + x[c], 18);
    }

    private static int rotateLeft(int value, int count) {
        return (value << count) | (value >>> (32 - count));
    }

    /**
     * Produces {@code length} bytes of the XSalsa20 keystream for counter starting
     * at zero. BouncyCastle's engine also starts its 64-bit block counter at zero
     * and increments it per 64-byte block.
     */
    private static byte[] xsalsa20Keystream(byte[] key32, byte[] nonce24, int length) {
        if (length == 0) {
            return new byte[0];
        }
        XSalsa20Engine engine = new XSalsa20Engine();
        engine.init(true, new ParametersWithIV(new KeyParameter(key32), nonce24));
        byte[] zeros = new byte[length];
        byte[] out = new byte[length];
        engine.processBytes(zeros, 0, length, out, 0);
        return out;
    }

    private static void poly1305(byte[] out16, byte[] key32, int keyOffset,
            byte[] message, int messageOffset, int messageLength) {
        byte[] polyKey = new byte[32];
        System.arraycopy(key32, keyOffset, polyKey, 0, 32);
        Poly1305 mac = new Poly1305();
        mac.init(new KeyParameter(polyKey));
        mac.update(message, messageOffset, messageLength);
        mac.doFinal(out16, 0);
        Arrays.fill(polyKey, (byte) 0);
    }

    private static int load32LE(byte[] input, int offset) {
        return (input[offset] & 0xff)
                | ((input[offset + 1] & 0xff) << 8)
                | ((input[offset + 2] & 0xff) << 16)
                | ((input[offset + 3] & 0xff) << 24);
    }

    private static void store32LE(byte[] output, int offset, int value) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static void checkLength(byte[] value, int expected, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        if (value.length != expected) {
            throw new IllegalArgumentException(name + " must be " + expected + " bytes, got "
                    + value.length);
        }
    }
}
