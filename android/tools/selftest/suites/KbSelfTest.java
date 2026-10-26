// Migrated from A:\Downloads\tmpscripts\kb\KbSelfTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.session.InputMessages;

import hbb.MessageOuterClass.ControlKey;
import hbb.MessageOuterClass.Message;

public final class KbSelfTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Message parsedReturn = Message.parseFrom(
                InputMessages.controlKeyPress(ControlKey.Return).toByteArray());

        check("controlKeyPress(Return) hasKeyEvent", parsedReturn.hasKeyEvent());
        check("controlKeyPress(Return) press==true",
                parsedReturn.getKeyEvent().getPress());
        check("controlKeyPress(Return) controlKey==Return",
                parsedReturn.getKeyEvent().getControlKey() == ControlKey.Return);
        check("controlKeyPress(Return) down is unset",
                !parsedReturn.getKeyEvent().getDown());

        Message parsedHotkey = Message.parseFrom(
                InputMessages.hotkey(0x41, 0x61).toByteArray());
        check("hotkey(0x41,0x61) hasWin2WinHotkey",
                parsedHotkey.getKeyEvent().hasWin2WinHotkey());
        check("hotkey(0x41,0x61) value",
                parsedHotkey.getKeyEvent().getWin2WinHotkey() == ((0x41 << 16) | 0x61));

        Message parsedText = Message.parseFrom(InputMessages.text("abc").toByteArray());
        check("text(\"abc\") seq", parsedText.getKeyEvent().getSeq().equals("abc"));

        if (failures == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: " + failures + " FAILED");
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            System.out.println("[PASS] " + label);
        } else {
            failures++;
            System.out.println("[FAIL] " + label);
        }
    }
}
