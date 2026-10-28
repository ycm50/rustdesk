package com.rustdesk.java.session;

/**
 * Windows scan codes (set 1) for the keys the on-screen keyboard offers, plus whether a character
 * sits on the shifted level of its key on a US layout.
 *
 * <p>A key event in Map mode carries the scan code in {@code chr} (message.proto: "position key
 * code. win: scancode"), and the host injects it with {@code sim_rdev_rawkey_position}, which is
 * stateful: the peer sees a real press and release. That is what makes several keys work at once
 * (Shift+A, Ctrl+C, two arrows) instead of the peer typing one character per message.
 */
public final class ScanCodes {

    private ScanCodes() {}

    /** @return the Windows scan code for {@code c}, or 0 when the key has none */
    public static int of(char c) {
        switch (Character.toLowerCase(c)) {
            case 'a': return 0x1E;
            case 'b': return 0x30;
            case 'c': return 0x2E;
            case 'd': return 0x20;
            case 'e': return 0x12;
            case 'f': return 0x21;
            case 'g': return 0x22;
            case 'h': return 0x23;
            case 'i': return 0x17;
            case 'j': return 0x24;
            case 'k': return 0x25;
            case 'l': return 0x26;
            case 'm': return 0x32;
            case 'n': return 0x31;
            case 'o': return 0x18;
            case 'p': return 0x19;
            case 'q': return 0x10;
            case 'r': return 0x13;
            case 's': return 0x1F;
            case 't': return 0x14;
            case 'u': return 0x16;
            case 'v': return 0x2F;
            case 'w': return 0x11;
            case 'x': return 0x2D;
            case 'y': return 0x15;
            case 'z': return 0x2C;
            case '1': case '!': return 0x02;
            case '2': case '@': return 0x03;
            case '3': case '#': return 0x04;
            case '4': case '$': return 0x05;
            case '5': case '%': return 0x06;
            case '6': case '^': return 0x07;
            case '7': case '&': return 0x08;
            case '8': case '*': return 0x09;
            case '9': case '(': return 0x0A;
            case '0': case ')': return 0x0B;
            case '-': case '_': return 0x0C;
            case '=': case '+': return 0x0D;
            case '[': case '{': return 0x1A;
            case ']': case '}': return 0x1B;
            case '\\': case '|': return 0x2B;
            case ';': case ':': return 0x27;
            case '\'': case '"': return 0x28;
            case '`': case '~': return 0x29;
            case ',': case '<': return 0x33;
            case '.': case '>': return 0x34;
            case '/': case '?': return 0x35;
            case ' ': return 0x39;
            default: return 0;
        }
    }

    /** @return true when the character only exists with Shift held */
    public static boolean needsShift(char c) {
        if (Character.isUpperCase(c)) return true;
        switch (c) {
            case '!': case '@': case '#': case '$': case '%': case '^': case '&':
            case '*': case '(': case ')': case '_': case '+': case '{': case '}':
            case '|': case ':': case '"': case '~': case '<': case '>': case '?':
                return true;
            default:
                return false;
        }
    }
}
