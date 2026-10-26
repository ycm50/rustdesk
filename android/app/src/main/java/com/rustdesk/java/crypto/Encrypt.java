package com.rustdesk.java.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Java port of RustDesk's {@code hbb_common::tcp::Encrypt}.
 *
 * <p>Version 0 uses one key for both directions. Version 1 splits the exchanged
 * key into a send key and a receive key with keyed BLAKE2b-256 over the key
 * exchange transcript, so the two directions never share a key.</p>
 *
 * <p>Each direction has its own counter starting at zero and incremented before
 * every message. The nonce is 24 zero bytes with the little-endian u64 counter in
 * the first 8 bytes, matching {@code FramedStream::get_nonce} in Rust.</p>
 *
 * <p>The methods are synchronized because the counters are mutable state. A
 * single instance must be used for one direction pair, exactly like the Rust
 * structure.</p>
 */
public final class Encrypt {

    /** Highest key-exchange version this implementation speaks. */
    public static final int KX_VERSION_LATEST = 1;

    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 24;
    public static final int MAC_BYTES = 16;

    private static final byte[] KX_SPLIT_CONTEXT =
            "rdkx-spl".getBytes(StandardCharsets.US_ASCII);
    private static final int KX_SPLIT_INITIATOR = 1;
    private static final int KX_SPLIT_RESPONDER = 2;
    private static final byte[] ZERO_NONCE = new byte[NONCE_BYTES];

    private final byte[] sendKey;
    private final byte[] recvKey;
    private final boolean split;

    private long sendSeq;
    private long recvSeq;

    /**
     * Version 0 constructor: one key used for both directions, with independent
     * send and receive counters.
     *
     * @param key32 32-byte secret key
     */
    public Encrypt(byte[] key32) {
        if (key32 == null) {
            throw new NullPointerException("key");
        }
        if (key32.length != KEY_BYTES) {
            throw new IllegalArgumentException("key must be 32 bytes, got " + key32.length);
        }
        this.sendKey = key32.clone();
        this.recvKey = this.sendKey;
        this.split = false;
    }

    private Encrypt(byte[] sendKey, byte[] recvKey, boolean split) {
        this.sendKey = sendKey;
        this.recvKey = recvKey;
        this.split = split;
    }

    /**
     * Version 1 constructor: derives one subkey per direction.
     *
     * @param key32       32-byte exchanged key
     * @param isInitiator true on the side that sent the sealed key
     * @param transcript  key-exchange transcript both sides committed to
     * @return an Encrypt whose send key is the initiator subkey for the initiator
     *         and the responder subkey for the responder
     */
    public static Encrypt newSplit(byte[] key32, boolean isInitiator, Transcript transcript) {
        if (transcript == null) {
            throw new NullPointerException("transcript");
        }
        byte[] initiatorKey = deriveSubkey(key32, KX_SPLIT_INITIATOR, transcript);
        byte[] responderKey;
        try {
            responderKey = deriveSubkey(key32, KX_SPLIT_RESPONDER, transcript);
        } catch (RuntimeException e) {
            Arrays.fill(initiatorKey, (byte) 0);
            throw e;
        }
        if (isInitiator) {
            return new Encrypt(initiatorKey, responderKey, true);
        }
        return new Encrypt(responderKey, initiatorKey, true);
    }

    /**
     * Convenience overload of {@link #newSplit(byte[], boolean, Transcript)}.
     */
    public static Encrypt newSplit(byte[] key32, boolean isInitiator, byte[] initiatorPk32,
            byte[] responderPk32, int advertised, int picked) {
        return newSplit(key32, isInitiator,
                new Transcript(initiatorPk32, responderPk32, advertised, picked));
    }

    /**
     * Encrypts one frame and advances the send counter.
     *
     * @param data plaintext (may be empty)
     * @return tag(16) || ciphertext
     */
    public synchronized byte[] enc(byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        sendSeq++;
        return Sodium.secretboxSeal(data, nonce(sendSeq), sendKey);
    }

    /**
     * Decrypts one frame and advances the receive counter.
     *
     * <p>Mirrors the Rust code: a frame of zero or one byte is returned unchanged
     * without advancing the counter. All other frames are authenticated before
     * they are decrypted.</p>
     *
     * @param data tag(16) || ciphertext
     * @return plaintext
     * @throws Sodium.DecryptionException if the tag does not verify
     */
    public synchronized byte[] dec(byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (data.length <= 1) {
            return data.clone();
        }
        recvSeq++;
        byte[] key = split ? recvKey : sendKey;
        return Sodium.secretboxOpen(data, nonce(recvSeq), key);
    }

    /**
     * Unseals the handshake's symmetric key, as Rust's {@code Encrypt::decode} does.
     *
     * @param sealedKey   sealed 32-byte key (tag || ciphertext) opened with a zero nonce
     * @param theirPk32   peer Curve25519 public key
     * @param ourSk32     own Curve25519 secret key
     * @return the 32-byte secret key extracted from the box
     */
    public static byte[] decode(byte[] sealedKey, byte[] theirPk32, byte[] ourSk32) {
        if (sealedKey == null) {
            throw new NullPointerException("sealedKey");
        }
        if (sealedKey.length != MAC_BYTES + KEY_BYTES) {
            throw new IllegalArgumentException("sealed key must be 48 bytes, got "
                    + sealedKey.length);
        }
        byte[] key = Sodium.boxOpen(sealedKey, ZERO_NONCE, theirPk32, ourSk32);
        if (key.length != KEY_BYTES) {
            Arrays.fill(key, (byte) 0);
            throw new IllegalArgumentException("decoded key is not 32 bytes");
        }
        return key;
    }

    /** The highest key-exchange version both sides support. */
    public static int kxVersionFor(int advertised) {
        // Rust's advertised is a u32; mask so a value with the high bit set is
        // still treated as larger than KX_VERSION_LATEST.
        long unsigned = advertised & 0xffffffffL;
        return (int) Math.min(unsigned, KX_VERSION_LATEST);
    }

    /** @return a copy of the key used to encrypt outgoing frames */
    public synchronized byte[] sendKey() {
        return sendKey.clone();
    }

    /** @return a copy of the key used to decrypt incoming frames */
    public synchronized byte[] recvKey() {
        return split ? recvKey.clone() : sendKey.clone();
    }

    public synchronized long sendCounter() {
        return sendSeq;
    }

    public synchronized long recvCounter() {
        return recvSeq;
    }

    public synchronized boolean hasSplitKeys() {
        return split;
    }

    private static byte[] deriveSubkey(byte[] key32, int direction, Transcript transcript) {
        if (key32 == null) {
            throw new NullPointerException("key");
        }
        if (key32.length != KEY_BYTES) {
            throw new IllegalArgumentException("key must be 32 bytes, got " + key32.length);
        }
        if (transcript.initiatorPk == null || transcript.initiatorPk.length != KEY_BYTES) {
            throw new IllegalArgumentException("transcript initiator public key must be 32 bytes");
        }
        if (transcript.responderPk == null || transcript.responderPk.length != KEY_BYTES) {
            throw new IllegalArgumentException("transcript responder public key must be 32 bytes");
        }
        return Sodium.blake2b(KEY_BYTES, key32,
                KX_SPLIT_CONTEXT,
                new byte[] { (byte) direction },
                le32(transcript.advertised),
                le32(transcript.picked),
                transcript.initiatorPk,
                transcript.responderPk);
    }

    private static byte[] nonce(long sequence) {
        byte[] nonce = new byte[NONCE_BYTES];
        for (int i = 0; i < 8; i++) {
            nonce[i] = (byte) (sequence >>> (8 * i));
        }
        return nonce;
    }

    private static byte[] le32(int value) {
        return new byte[] {
            (byte) value,
            (byte) (value >>> 8),
            (byte) (value >>> 16),
            (byte) (value >>> 24)
        };
    }

    /**
     * What both sides saw during the key exchange, each from its own side of the
     * wire. Field names and semantics match Rust's {@code KxTranscript}.
     */
    public static final class Transcript {
        /** Ephemeral public key of the side that sent the sealed key. */
        public final byte[] initiatorPk;
        /** Ephemeral public key the sealed key was sealed to. */
        public final byte[] responderPk;
        /** Version the responder advertised. */
        public final int advertised;
        /** Version the initiator picked. */
        public final int picked;

        public Transcript(byte[] initiatorPk, byte[] responderPk, int advertised, int picked) {
            this.initiatorPk = initiatorPk == null ? null : initiatorPk.clone();
            this.responderPk = responderPk == null ? null : responderPk.clone();
            this.advertised = advertised;
            this.picked = picked;
        }
    }
}
