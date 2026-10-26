package com.rustdesk.java.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Standalone self-test for the libsodium-compatible primitives and the RustDesk
 * {@link Encrypt} port. It only uses the JDK and BouncyCastle, so it runs both on
 * a desktop JDK and on Android without touching any Android API.
 *
 * <p>Run with:</p>
 * <pre>
 * javac -cp bcprov-jdk18on-1.79.jar -d out *.java
 * java -cp "out;bcprov-jdk18on-1.79.jar" com.rustdesk.java.crypto.SelfTest
 * </pre>
 */
public final class SelfTest {

    private static int checks;
    private static int failures;
    private static int requiredVectors;
    private static int requiredPassed;

    private SelfTest() {
    }

    public static void main(String[] args) {
        System.out.println("=== RustDesk Java crypto self-test ===");
        System.out.println();

        testV0WireVector();
        testSplitWireVectors();
        testRoundTrips();
        testBox();
        testBlake2b();

        System.out.println();
        System.out.println("Required wire vectors (v0 + 4 split keys): " + requiredPassed + "/"
                + requiredVectors + " PASS");
        System.out.println("Checks: " + checks + ", failures: " + failures);
        if (failures != 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: ALL TESTS PASSED");
    }

    // ------------------------------------------------------------------
    // Required fixed vectors
    // ------------------------------------------------------------------

    private static void testV0WireVector() {
        System.out.println("-- Version 0 wire vector --");
        Encrypt encrypt = new Encrypt(key(0x11));
        byte[] sealed = encrypt.enc(ascii("hello"));
        checkRequired("v0: Encrypt(key=0x11*32).enc(\"hello\")",
                "3b768f827fdcc4af555b9e42533be6611e2c333481", sealed);
        System.out.println();
    }

    private static void testSplitWireVectors() {
        System.out.println("-- Version 1 split wire vectors --");
        byte[] initiatorPk = key(0x22);
        byte[] responderPk = key(0x33);

        Encrypt splitA = Encrypt.newSplit(key(0x11), true,
                new Encrypt.Transcript(initiatorPk, responderPk, 1, 1));
        checkRequired("v1 advertised=1, picked=1, initiator send key",
                "c189ec6e1935c0751cfc85a0f075405cae507d7925bb19687caf56cbb54e0ecf",
                splitA.sendKey());
        checkRequired("v1 advertised=1, picked=1, initiator receive key",
                "8a62648e9195cb10ea900c0a24d2a166c1e982347a2dc6833c3e756bf5715d36",
                splitA.recvKey());

        Encrypt splitB = Encrypt.newSplit(key(0x11), true,
                new Encrypt.Transcript(initiatorPk, responderPk, 3, 1));
        checkRequired("v1 advertised=3, picked=1, initiator send key",
                "25b0778ae600f9731c7fa457d1cc5d40071b0aa3d34c90b53e1d5344b0e7123a",
                splitB.sendKey());
        checkRequired("v1 advertised=3, picked=1, initiator receive key",
                "5bbde0ac6a708d4cd1ff807125d79cb6030bde62f7a66b6cc1a79e31bd657756",
                splitB.recvKey());

        // The responder must use the mirror image of the initiator's keys.
        Encrypt responder = Encrypt.newSplit(key(0x11), false,
                new Encrypt.Transcript(initiatorPk, responderPk, 1, 1));
        checkBytes("v1 responder send key == initiator receive key",
                splitA.recvKey(), responder.sendKey());
        checkBytes("v1 responder receive key == initiator send key",
                splitA.sendKey(), responder.recvKey());

        // Short public keys must be rejected.
        try {
            Encrypt.newSplit(key(0x11), true, new byte[31], responderPk, 1, 1);
            fail("v1 short initiator public key rejected");
        } catch (IllegalArgumentException expected) {
            pass("v1 short initiator public key rejected");
        }

        check("kxVersionFor(0)", 0L, Encrypt.kxVersionFor(0));
        check("kxVersionFor(1)", 1L, Encrypt.kxVersionFor(1));
        check("kxVersionFor(3)", 1L, Encrypt.kxVersionFor(3));
        check("kxVersionFor(0xffffffff)", 1L, Encrypt.kxVersionFor(0xffffffff));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Round trips, counters and tampering
    // ------------------------------------------------------------------

    private static void testRoundTrips() {
        System.out.println("-- Version 0 / version 1 round trips --");

        byte[] message = buildMessage(300);
        Encrypt v0Sender = new Encrypt(key(0x11));
        Encrypt v0Receiver = new Encrypt(key(0x11));
        for (int frame = 0; frame < 3; frame++) {
            byte[] sealed = v0Sender.enc(message);
            byte[] opened = v0Receiver.dec(sealed);
            checkBytes("v0 round trip frame " + (frame + 1), message, opened);
        }

        Encrypt.Transcript transcript = new Encrypt.Transcript(key(0x22), key(0x33), 1, 1);
        Encrypt initiator = Encrypt.newSplit(key(0x11), true, transcript);
        Encrypt responder = Encrypt.newSplit(key(0x11), false, transcript);

        for (int frame = 0; frame < 3; frame++) {
            byte[] sealed = initiator.enc(message);
            checkBytes("v1 initiator->responder frame " + (frame + 1), message,
                    responder.dec(sealed));
            byte[] back = responder.enc(message);
            checkBytes("v1 responder->initiator frame " + (frame + 1), message,
                    initiator.dec(back));
        }

        // First v0 frame uses nonce counter 1.
        byte[] firstNonce = new byte[24];
        firstNonce[0] = 1;
        Encrypt fresh = new Encrypt(key(0x11));
        byte[] firstFrame = fresh.enc(ascii("hello"));
        checkBytes("v0 first frame decrypts with nonce counter 1", ascii("hello"),
                Sodium.secretboxOpen(firstFrame, firstNonce, key(0x11)));
        check("v0 send counter", 1L, fresh.sendCounter());

        // Counter 256 exercises the little-endian encoding of the second nonce byte.
        Encrypt counterSender = new Encrypt(key(0x11));
        byte[] frame256 = null;
        for (int i = 0; i < 256; i++) {
            frame256 = counterSender.enc(ascii("hello"));
        }
        check("v0 frame 256 little-endian counter vector",
                "3e05dbf6656fd26b072f36aab113cd9836e72b7b3c", frame256);

        // Tampering must be detected and must not return plaintext.
        Encrypt tamperSender = new Encrypt(key(0x11));
        Encrypt tamperReceiver = new Encrypt(key(0x11));
        byte[] tampered = tamperSender.enc(ascii("hello"));
        tampered[tampered.length - 1] ^= 0x01;
        try {
            tamperReceiver.dec(tampered);
            fail("v0 tampered frame rejected");
        } catch (Sodium.DecryptionException expected) {
            pass("v0 tampered frame rejected");
        }

        // The Rust code returns 0/1-byte frames untouched.
        checkBytes("v0 1-byte frame passthrough", new byte[] { 0x7f },
                new Encrypt(key(0x11)).dec(new byte[] { 0x7f }));

        // Empty and block-boundary messages.
        Encrypt emptySender = new Encrypt(key(0x11));
        Encrypt emptyReceiver = new Encrypt(key(0x11));
        byte[] emptySealed = emptySender.enc(new byte[0]);
        check("empty message sealed length", 16L, emptySealed.length);
        checkBytes("empty message round trip", new byte[0], emptyReceiver.dec(emptySealed));

        for (int length : new int[] { 1, 31, 32, 33, 63, 64, 65, 1000 }) {
            byte[] body = buildMessage(length);
            byte[] nonce = new byte[24];
            byte[] sealed = Sodium.secretboxSeal(body, nonce, key(0x44));
            checkBytes("secretbox length " + length, body,
                    Sodium.secretboxOpen(sealed, nonce, key(0x44)));
        }

        // Independently generated (reference implementation) vector that crosses the
        // 64-byte block boundary, so the XSalsa20 block counter is exercised.
        check("secretbox 100-byte fixed vector",
                "747e0348b019dcffb71aae683fe4d4be003819e4e4ef1a644db187882ea1439c"
                        + "6fc925524b28a9e79bc63c83f978cb2dc44e35b632be8498f776c924cb2cfb36"
                        + "ccc2206a87f29089bf669c3c0edcb9cabc7ec70eec84ffd34a41d1e5f8c86976"
                        + "a1492bb7075ac4bd8c89eb51aed50a36f1da26e4",
                Sodium.secretboxSeal(buildMessage(100), new byte[24], key(0x44)));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // crypto_box
    // ------------------------------------------------------------------

    private static void testBox() {
        System.out.println("-- crypto_box --");
        byte[] aliceSk = hexToBytes(
                "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] alicePk = hexToBytes(
                "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a");
        byte[] bobSk = hexToBytes(
                "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
        byte[] bobPk = hexToBytes(
                "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");

        checkBytes("boxPublicKey(aliceSk)", alicePk, Sodium.boxPublicKey(aliceSk));
        checkBytes("boxPublicKey(bobSk)", bobPk, Sodium.boxPublicKey(bobSk));

        byte[] expectedShared = hexToBytes(
                "1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389");
        checkBytes("boxBeforenm(bobPk, aliceSk) (NaCl test key)", expectedShared,
                Sodium.boxBeforenm(bobPk, aliceSk));
        checkBytes("boxBeforenm(alicePk, bobSk)", expectedShared,
                Sodium.boxBeforenm(alicePk, bobSk));

        byte[] message = ascii("libsodium box compatibility");
        byte[] nonce = new byte[24];
        byte[] sealed = Sodium.boxSeal(message, nonce, bobPk, aliceSk);
        check("box sealed length", (long) (16 + message.length), sealed.length);
        checkBytes("box round trip", message, Sodium.boxOpen(sealed, nonce, alicePk, bobSk));

        sealed[sealed.length - 1] ^= 0x01;
        try {
            Sodium.boxOpen(sealed, nonce, alicePk, bobSk);
            fail("box tampered message rejected");
        } catch (Sodium.DecryptionException expected) {
            pass("box tampered message rejected");
        }

        byte[][] generated = Sodium.genBoxKeyPair();
        check("genBoxKeyPair public key length", 32L, generated[0].length);
        check("genBoxKeyPair secret key length", 32L, generated[1].length);
        byte[] generatedSealed = Sodium.boxSeal(message, nonce, generated[0], aliceSk);
        checkBytes("box with generated peer key", message,
                Sodium.boxOpen(generatedSealed, nonce, alicePk, generated[1]));

        // Handshake decode helper: seal a 32-byte key with a zero nonce, then decode.
        byte[] handshakeKey = buildMessage(32);
        byte[] sealedKey = Sodium.boxSeal(handshakeKey, nonce, bobPk, aliceSk);
        checkBytes("Encrypt.decode", handshakeKey,
                Encrypt.decode(sealedKey, alicePk, bobSk));
        System.out.println();
    }

    // ------------------------------------------------------------------
    // BLAKE2b
    // ------------------------------------------------------------------

    private static void testBlake2b() {
        System.out.println("-- BLAKE2b --");
        check("BLAKE2b-256(\"\")",
                "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
                Sodium.blake2b(32, null));
        check("BLAKE2b-512(\"abc\")",
                "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c539"
                        + "2aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
                Sodium.blake2b(64, null, ascii("abc")));
        check("BLAKE2b keyed output length", 32L,
                Sodium.blake2b(32, key(0x11), ascii("rdkx-spl")).length);
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void checkRequired(String label, String expectedHex, byte[] actual) {
        requiredVectors++;
        checks++;
        String actualHex = hex(actual);
        if (expectedHex.equals(actualHex)) {
            requiredPassed++;
            System.out.println("[PASS] " + label);
            System.out.println("       " + actualHex);
        } else {
            failures++;
            System.out.println("[FAIL] " + label);
            System.out.println("       expected: " + expectedHex);
            System.out.println("       actual  : " + actualHex);
        }
    }

    private static void checkBytes(String label, byte[] expected, byte[] actual) {
        checks++;
        if (Arrays.equals(expected, actual)) {
            System.out.println("[PASS] " + label);
        } else {
            failures++;
            System.out.println("[FAIL] " + label);
            System.out.println("       expected: " + hex(expected));
            System.out.println("       actual  : " + hex(actual));
        }
    }

    private static void check(String label, long expected, long actual) {
        checks++;
        if (expected == actual) {
            System.out.println("[PASS] " + label);
        } else {
            failures++;
            System.out.println("[FAIL] " + label + " (expected " + expected + ", got " + actual
                    + ")");
        }
    }

    private static void check(String label, String expectedHex, byte[] actual) {
        checks++;
        String actualHex = hex(actual);
        if (expectedHex.equals(actualHex)) {
            System.out.println("[PASS] " + label);
        } else {
            failures++;
            System.out.println("[FAIL] " + label);
            System.out.println("       expected: " + expectedHex);
            System.out.println("       actual  : " + actualHex);
        }
    }

    private static void pass(String label) {
        checks++;
        System.out.println("[PASS] " + label);
    }

    private static void fail(String label) {
        checks++;
        failures++;
        System.out.println("[FAIL] " + label);
    }

    private static byte[] buildMessage(int length) {
        byte[] message = new byte[length];
        for (int i = 0; i < length; i++) {
            message[i] = (byte) (i * 31 + 7);
        }
        return message;
    }

    private static byte[] key(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = alphabet[value >>> 4];
            out[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(out);
    }

    private static byte[] hexToBytes(String text) {
        if ((text.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16);
            int low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid hex");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
