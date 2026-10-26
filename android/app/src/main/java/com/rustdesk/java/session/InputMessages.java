package com.rustdesk.java.session;

import hbb.MessageOuterClass.ControlKey;
import hbb.MessageOuterClass.KeyEvent;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.MouseEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds outgoing input messages.
 *
 * <p>RustDesk packs the button state and the event type into one field: the host reads
 * {@code buttons = mask >> 3} and {@code type = mask & 0x7} (src/server/input_service.rs).
 */
public final class InputMessages {

    /**
     * Keyboard mode applied to every key event we build, mirroring RustDesk's three modes:
     * Legacy (scancode based, the peer's layout decides), Map (map the local layout onto the
     * peer's) and Translate (send the text and let the peer's keyboard backend type it, which is
     * what non-ASCII input needs). The host reads it from {@code KeyEvent.mode}.
     */
    private static volatile hbb.MessageOuterClass.KeyboardMode keyboardMode =
            hbb.MessageOuterClass.KeyboardMode.Translate;

    public static void setKeyboardMode(hbb.MessageOuterClass.KeyboardMode mode) {
        if (mode != null) {
            keyboardMode = mode;
        }
    }

    public static hbb.MessageOuterClass.KeyboardMode getKeyboardMode() {
        return keyboardMode;
    }
    public static final int TYPE_MOVE = 0;
    public static final int TYPE_DOWN = 1;
    public static final int TYPE_UP = 2;
    public static final int TYPE_WHEEL = 3;
    public static final int TYPE_TRACKPAD = 4;

    private static final int TYPE_MASK = 0x7;

    private InputMessages() {}

    public static int mask(int eventType, int buttons) {
        return ((buttons & 0xFF) << 3) | (eventType & TYPE_MASK);
    }

    public static Message mouse(int eventType, int buttons, int x, int y) {
        MouseEvent event = MouseEvent.newBuilder()
                .setMask(mask(eventType, buttons))
                .setX(x)
                .setY(y)
                .build();
        return Message.newBuilder().setMouseEvent(event).build();
    }

    public static Message mouseMove(int x, int y) {
        return mouse(TYPE_MOVE, 0, x, y);
    }

    public static Message mouseDown(int x, int y, int buttons) {
        return mouse(TYPE_DOWN, buttons, x, y);
    }

    public static Message mouseUp(int x, int y, int buttons) {
        return mouse(TYPE_UP, buttons, x, y);
    }

    /**
     * Wheel events carry the signed notches in the coordinate fields; the protocol has no wheel
     * position (the host scrolls wherever its cursor is, src/server/input_service.rs:1413).
     */
    public static Message wheel(int deltaX, int deltaY) {
        return mouse(TYPE_WHEEL, 0, deltaX, deltaY);
    }

    /**
     * Types text on the peer. The host injects a {@code seq} key event through its keyboard
     * backend (src/server/input_service.rs:translate_keyboard_mode), which is how the reference
     * client sends IME input too (src/ui_session_interface.rs:input_string).
     */
    public static Message text(String value) {
        KeyEvent event = KeyEvent.newBuilder().setSeq(value).setMode(keyboardMode).build();
        return Message.newBuilder().setKeyEvent(event).build();
    }

    /** Backspace, arrows and friends go through {@code control_key}. */
    public static Message controlKey(hbb.MessageOuterClass.ControlKey key, boolean down) {
        KeyEvent event = KeyEvent.newBuilder()
                .setControlKey(key)
                .setDown(down)
                .setPress(false)
                .build();
        return Message.newBuilder().setKeyEvent(event).build();
    }

    /**
     * A tap: the host treats {@code press} as a full down+up cycle, so {@code down} stays unset
     * (src/ui_session_interface.rs sets only {@code press} for a click).
     */
    public static Message controlKeyPress(ControlKey key) {
        KeyEvent event = KeyEvent.newBuilder()
                .setControlKey(key)
                .setPress(true)
                .build();
        return Message.newBuilder().setKeyEvent(event).build();
    }

    public static Message unicode(char c) {
        KeyEvent event = KeyEvent.newBuilder()
                .setUnicode(c)
                .build();
        return Message.newBuilder().setKeyEvent(event).build();
    }

    public static Message chr(int code) {
        KeyEvent event = KeyEvent.newBuilder()
                .setChr(code)
                .build();
        return Message.newBuilder().setKeyEvent(event).build();
    }

    /**
     * Windows-to-Windows shortcut: the host splits the value into a virtual key in the high
     * word and a unicode code point in the low word (src/server/input_service.rs:
     * simulate_win2win_hotkey).
     */
    public static Message hotkey(int virtualKey, int unicode) {
        int code = ((virtualKey & 0xFFFF) << 16) | (unicode & 0xFFFF);
        KeyEvent event = KeyEvent.newBuilder()
                .setWin2WinHotkey(code)
                .build();
        return Message.newBuilder().setKeyEvent(event).build();
    }

    private static final Map<String, ControlKey> CONTROL_KEY_BY_NAME = buildControlKeyMap();

    private static Map<String, ControlKey> buildControlKeyMap() {
        Map<String, ControlKey> map = new LinkedHashMap<>();
        map.put("Return", ControlKey.Return);
        map.put("Tab", ControlKey.Tab);
        map.put("Escape", ControlKey.Escape);
        map.put("Backspace", ControlKey.Backspace);
        map.put("Delete", ControlKey.Delete);
        map.put("Home", ControlKey.Home);
        map.put("End", ControlKey.End);
        map.put("PageUp", ControlKey.PageUp);
        map.put("PageDown", ControlKey.PageDown);
        map.put("UpArrow", ControlKey.UpArrow);
        map.put("DownArrow", ControlKey.DownArrow);
        map.put("LeftArrow", ControlKey.LeftArrow);
        map.put("RightArrow", ControlKey.RightArrow);
        map.put("Insert", ControlKey.Insert);
        map.put("F1", ControlKey.F1);
        map.put("F2", ControlKey.F2);
        map.put("F3", ControlKey.F3);
        map.put("F4", ControlKey.F4);
        map.put("F5", ControlKey.F5);
        map.put("F6", ControlKey.F6);
        map.put("F7", ControlKey.F7);
        map.put("F8", ControlKey.F8);
        map.put("F9", ControlKey.F9);
        map.put("F10", ControlKey.F10);
        map.put("F11", ControlKey.F11);
        map.put("F12", ControlKey.F12);
        map.put("Shift", ControlKey.Shift);
        map.put("Control", ControlKey.Control);
        map.put("Alt", ControlKey.Alt);
        map.put("CapsLock", ControlKey.CapsLock);
        return Collections.unmodifiableMap(map);
    }

    /**
     * Names usable as a control key. Grows only for members that the generated proto enum
     * actually has; the map is read-only so callers cannot corrupt it.
     */
    public static Map<String, ControlKey> controlKeyMap() {
        return CONTROL_KEY_BY_NAME;
    }
    /**
     * Stamps the current keyboard mode onto a key event built elsewhere (the physical key mapper
     * builds its own messages). Keeping it here means one place owns the mode instead of every
     * builder remembering it.
     */
    public static Message stamp(Message message) {
        if (message == null || !message.hasKeyEvent()) {
            return message;
        }
        KeyEvent event = message.getKeyEvent();
        // Control keys (Backspace, Return, arrows...) exist only in the peer's legacy keyboard
        // path: its translate path has no branch for them and drops the event as unexpected.
        // Text keeps the configured mode, which is what makes IME input work.
        hbb.MessageOuterClass.KeyboardMode mode =
                event.getUnionCase() == KeyEvent.UnionCase.CONTROL_KEY
                        ? hbb.MessageOuterClass.KeyboardMode.Legacy
                        : keyboardMode;
        if (event.getMode() == mode) {
            return message;
        }
        return Message.newBuilder()
                .setKeyEvent(event.toBuilder().setMode(mode))
                .build();
    }
}
