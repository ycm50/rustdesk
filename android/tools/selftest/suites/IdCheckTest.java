// Migrated from A:\Downloads\tmpscripts\main\IdCheckTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

public final class IdCheckTest {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("== MainActivity.isValidPeerId (pure JVM, no android.jar) ==");

        expectTrue("123456789");
        expectTrue("1234567890");

        expectFalse("12345678");
        expectFalse("12345678901");
        expectFalse("abc");
        expectFalse("");
        expectFalse(null);

        expectTrue("192.168.1.10");
        expectTrue("192.168.1.10:21118");
        expectTrue("[::1]:21118");

        expectFalse("999.1.1.1");
        expectFalse("1.2.3");
        expectFalse("1.2.3.4:abc");

        // Extra: surrounding whitespace is trimmed before the rules run.
        expectTrue(" 123456789 ");

        if (failures == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: " + failures + " FAILED");
            System.exit(1);
        }
    }

    private static void expectTrue(String value) {
        check(value, true);
    }

    private static void expectFalse(String value) {
        check(value, false);
    }

    private static void check(String value, boolean expected) {
        boolean actual = PeerIdRules.isValidPeerId(value);
        boolean pass = actual == expected;
        if (!pass) failures++;
        System.out.println((pass ? "[PASS] " : "[FAIL] ")
                + "isValidPeerId(" + quote(value) + ") = " + actual
                + ", expected " + expected);
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
