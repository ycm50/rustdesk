// Migrated from A:\Downloads\tmpscripts\hostsess\Ed25519Test.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.crypto.Ed25519;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Standalone self-test for the new ed25519 signing API.
 *
 * <p>Checks the libsodium layout ({@code signature(64) || message}), round-trip signing, tamper
 * rejection, key derivation, and one RFC 8032 fixed vector so the implementation is pinned to the
 * standard rather than only to itself. JVM only, no android.jar.</p>
 */
public final class Ed25519Test {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("Ed25519 self-test (JVM only, no android.jar)");
        System.out.println();

        testRoundTrip();
        testTamper();
        testSignedLayout();
        testKeyPair();
        testRfc8032Vector();
        testArgumentChecks();

        System.out.println();
        System.out.println("checks: " + checks + ", failures: " + failures);
        if (failures == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: FAILED (" + failures + " of " + checks + " checks)");
            System.exit(1);
        }
    }

    private static void testRoundTrip() {
        section("sign -> verify round trip");
        byte[][] keys = Ed25519.generateKeyPair();
        byte[] message = ascii("host signed id payload");
        byte[] signature = Ed25519.sign(message, keys[1]);
        check("signature is 64 bytes", signature.length == Ed25519.SIGNATURE_BYTES,
                signature.length + " bytes");
        byte[] signed = Ed25519.signed(message, keys[1]);
        byte[] recovered = null;
        try {
            recovered = Ed25519.verify(signed, keys[0]);
        } catch (IllegalArgumentException e) {
            // reported below
        }
        check("verify() accepts a signature made by the matching key", recovered != null, "");
        check("verify() returns the original message", Arrays.equals(message, recovered), "");
        check("sign() and signed()[0..64] agree", Arrays.equals(signature,
                Arrays.copyOfRange(signed, 0, Ed25519.SIGNATURE_BYTES)), "");
        check("publicKeyOf(seed) is the generated public key",
                Arrays.equals(keys[0], Ed25519.publicKeyOf(keys[1])), "");
    }

    private static void testTamper() {
        section("tamper rejection");
        byte[][] keys = Ed25519.generateKeyPair();
        byte[] message = ascii("a message that must not be modified");
        byte[] signed = Ed25519.signed(message, keys[1]);

        byte[] messageTampered = signed.clone();
        messageTampered[Ed25519.SIGNATURE_BYTES + 3] ^= 0x01;
        check("flipping one message byte fails verification", rejects(messageTampered, keys[0]), "");

        byte[] signatureTampered = signed.clone();
        signatureTampered[10] ^= 0x01;
        check("flipping one signature byte fails verification", rejects(signatureTampered, keys[0]), "");

        byte[][] other = Ed25519.generateKeyPair();
        check("verifying with a different key fails", rejects(signed, other[0]), "");

        byte[] truncated = Arrays.copyOf(signed, 40);
        check("a blob shorter than the signature is rejected", rejects(truncated, keys[0]), "");
    }

    private static void testSignedLayout() {
        section("signed() layout: signature || message");
        byte[][] keys = Ed25519.generateKeyPair();
        byte[] message = ascii("IdPk bytes go here");
        byte[] signed = Ed25519.signed(message, keys[1]);
        byte[] expected = Ed25519.sign(message, keys[1]);
        check("signed() length == 64 + message length",
                signed.length == Ed25519.SIGNATURE_BYTES + message.length, signed.length + " bytes");
        check("signed() first 64 bytes equal sign(message)", Arrays.equals(expected,
                Arrays.copyOfRange(signed, 0, Ed25519.SIGNATURE_BYTES)), "");
        check("signed() remainder equals the message", Arrays.equals(message,
                Arrays.copyOfRange(signed, Ed25519.SIGNATURE_BYTES, signed.length)), "");
        check("empty message still produces 64 bytes",
                Ed25519.signed(new byte[0], keys[1]).length == Ed25519.SIGNATURE_BYTES, "");
    }

    private static void testKeyPair() {
        section("key pair generation");
        byte[][] first = Ed25519.generateKeyPair();
        byte[][] second = Ed25519.generateKeyPair();
        check("public key is 32 bytes", first[0].length == Ed25519.PUBLIC_KEY_BYTES, "");
        check("private seed is 32 bytes", first[1].length == Ed25519.SEED_BYTES, "");
        check("two generated key pairs differ", !Arrays.equals(first[0], second[0])
                && !Arrays.equals(first[1], second[1]), "");
        byte[] message = ascii("key isolation");
        check("a signature from one key pair does not verify with the other",
                rejects(Ed25519.signed(message, first[1]), second[0]), "");
    }

    /** RFC 8032 section 7.1, TEST 1: pins the implementation to the standard. */
    private static void testRfc8032Vector() {
        section("RFC 8032 test vector 1 (empty message)");
        byte[] seed = hexToBytes(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] expectedPk = hexToBytes(
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] expectedSig = hexToBytes(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33"
                        + "bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        check("publicKeyOf(seed) matches RFC 8032", Arrays.equals(expectedPk,
                Ed25519.publicKeyOf(seed)), hex(Ed25519.publicKeyOf(seed)));
        check("sign(\"\", seed) matches RFC 8032", Arrays.equals(expectedSig,
                Ed25519.sign(new byte[0], seed)), hex(Ed25519.sign(new byte[0], seed)));
    }

    private static void testArgumentChecks() {
        section("argument checks");
        byte[][] keys = Ed25519.generateKeyPair();
        check("sign() rejects a 31-byte seed", throwsIae(keys[1], 31), "");
        check("sign() rejects a null seed", throwsNpe(keys[1]), "");
        check("verify() rejects a 31-byte public key", rejectsWithKey(
                Ed25519.signed(ascii("x"), keys[1]), 31), "");
    }

    // ------------------------------------------------------------- helpers

    private static boolean rejects(byte[] signed, byte[] publicKey) {
        try {
            Ed25519.verify(signed, publicKey);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean rejectsWithKey(byte[] signed, int keyLength) {
        try {
            Ed25519.verify(signed, new byte[keyLength]);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean throwsIae(byte[] seed, int length) {
        try {
            Ed25519.sign(ascii("x"), Arrays.copyOf(seed, length));
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean throwsNpe(byte[] seed) {
        try {
            Ed25519.sign(ascii("x"), null);
            return false;
        } catch (NullPointerException expected) {
            return true;
        }
    }

    private static byte[] ascii(String text) {
        return text.getBytes(ASCII);
    }

    private static byte[] hexToBytes(String text) {
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
}
