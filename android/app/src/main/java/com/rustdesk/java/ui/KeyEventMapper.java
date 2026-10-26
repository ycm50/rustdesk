package com.rustdesk.java.ui;

import android.view.KeyEvent;

import com.rustdesk.java.session.InputMessages;

import hbb.MessageOuterClass.ControlKey;
import hbb.MessageOuterClass.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure translation from {@link android.view.KeyEvent} to RustDesk key messages.
 *
 * <p>This class only builds protocol messages. It never touches a session, the network,
 * the activity or any Android view state, so it can be exercised on a plain JVM.
 *
 * <p>The caller owns transport: every message in a returned list must be sent to the
 * peer one by one and in list order, before the next input event is processed.
 *
 * <p>Encoding notes (the host side is src/server/input_service.rs):
 * <ul>
 *   <li>No message sets {@code mode}, so the host reads it as {@code KeyboardMode.Legacy}
 *       ({@code evt.mode.enum_value_or(KeyboardMode::Legacy)}) and runs
 *       {@code legacy_keyboard_mode}. That is the same mode the reference client uses
 *       (src/ui_session_interface.rs:_input_key).</li>
 *   <li>Special keys and modifier keys go through {@code control_key} with {@code down}.</li>
 *   <li>A shortcut is a {@code chr} carrying the character plus the held modifiers in
 *       {@code modifiers} and {@code down}. The host presses the listed modifiers for the
 *       chord ({@code press_modifiers}) and keys the character down while they are held
 *       ({@code process_chr(.., has_hotkey_modifiers(evt))}). {@code win2win_hotkey} is not
 *       usable here: it is only handled by {@code translate_keyboard_mode} and only on a
 *       Windows host, so a legacy client's message would be dropped.</li>
 *   <li>Plain text uses {@code unicode}, which the host types with
 *       {@code process_unicode} and which already carries its own case.</li>
 * </ul>
 *
 * <p>Return value conventions:
 * <ul>
 *   <li>{@code null} means "no remote action": the event is null, its action is neither
 *       down/up/multiple, or the key produces nothing the peer could apply. Callers should
 *       let the platform handle the event in that case.</li>
 *   <li>An empty list means "understood, but nothing to send": the ACTION_UP half of a
 *       plain character, which the peer typed on ACTION_DOWN. Callers consume the event.</li>
 * </ul>
 */
public final class KeyEventMapper {

    private KeyEventMapper() {}

    /**
     * Maps one Android key event.
     *
     * <p>The returned messages are ordered and must be sent in that order.
     *
     * @param event the Android event; may be null
     * @return the messages to send in order, an empty list when nothing needs to be sent,
     *         or null when the event needs no remote action at all
     */
    @SuppressWarnings("deprecation")
    public static List<Message> map(KeyEvent event) {
        if (event == null) {
            return null;
        }
        final int action = event.getAction();
        // Deprecated since API 29 (the IME commits text itself now), but a hardware keyboard
        // can still deliver committed text as one multi-character event.
        if (action == KeyEvent.ACTION_MULTIPLE) {
            return mapCharacters(event);
        }
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return null;
        }
        final boolean down = action == KeyEvent.ACTION_DOWN;
        final int keyCode = event.getKeyCode();
        final int metaState = event.getMetaState();
        final List<ControlKey> held = modifiers(metaState);

        // Special keys and the modifier keys themselves are stateless: down/up maps
        // directly onto control_key. The held modifiers ride along so that chords such as
        // Ctrl+Tab or Ctrl+Left reach the peer with their modifier still down.
        final ControlKey control = controlKeyFor(keyCode);
        if (control != null) {
            return Collections.singletonList(
                    legacy(InputMessages.controlKey(control, down), down, held));
        }

        if (hasCtrl(metaState) || hasAlt(metaState) || hasMeta(metaState)) {
            // A shortcut needs the character down while the modifiers are held, and it needs
            // the matching up as well, so both actions are sent.
            final int unicode = event.getUnicodeChar();
            if (unicode == 0) {
                return null;
            }
            return Collections.singletonList(legacy(InputMessages.chr(unicode), down, held));
        }

        // Plain text: the peer types the character on key-down alone. Sending the matching
        // key-up would type it a second time (process_unicode ignores down).
        if (!down) {
            return Collections.emptyList();
        }
        final int unicode = event.getUnicodeChar();
        if (unicode == 0) {
            // Not a character key (media keys, unknown codes, ...).
            return null;
        }
        return Collections.singletonList(InputMessages.unicode((char) unicode));
    }

    /**
     * Types the characters of an {@link KeyEvent#ACTION_MULTIPLE} event.
     *
     * <p>The characters are committed text (a hardware keyboard or an IME delivering several
     * code points at once), so each one is a separate {@code unicode} message.
     *
     * @return the messages, or null when the event carries no character
     */
    @SuppressWarnings("deprecation")
    private static List<Message> mapCharacters(KeyEvent event) {
        final String characters = event.getCharacters();
        if (characters == null || characters.isEmpty()) {
            return null;
        }
        final List<Message> messages = new ArrayList<>(characters.length());
        for (int i = 0; i < characters.length(); i++) {
            final Message message = typedChar(characters.charAt(i));
            if (message != null) {
                messages.add(message);
            }
        }
        return messages.isEmpty() ? null : messages;
    }

    /**
     * Maps an Android meta state to control-key modifiers.
     *
     * <p>The result is ordered Control, Shift, Alt, Meta and contains each modifier at
     * most once. Both the aggregate bits (META_CTRL_ON, ...) and the left/right bits
     * (META_CTRL_LEFT_ON, ...) are accepted. An empty list means no modifier is held.
     *
     * @param metaState a state such as {@link KeyEvent#getMetaState()}
     * @return an immutable list, empty when no modifier bit is set
     */
    public static List<ControlKey> modifiers(int metaState) {
        final List<ControlKey> result = new ArrayList<>(4);
        if (hasCtrl(metaState)) {
            result.add(ControlKey.Control);
        }
        if (hasShift(metaState)) {
            result.add(ControlKey.Shift);
        }
        if (hasAlt(metaState)) {
            result.add(ControlKey.Alt);
        }
        if (hasMeta(metaState)) {
            result.add(ControlKey.Meta);
        }
        if (result.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Maps a printable character committed by the soft keyboard or an IME.
     *
     * <p>Printable characters are sent as {@code unicode}. A caller that receives a
     * whole string should send one message per code unit.
     *
     * @param c the character; {@code '\0'} is not a character
     * @return the message to send, or null for {@code '\0'}
     */
    public static Message typedChar(char c) {
        if (c == 0) {
            return null;
        }
        return InputMessages.unicode(c);
    }

    /**
     * Adds the legacy-mode {@code down} flag and the held modifiers to a key message.
     *
     * <p>{@code InputMessages.controlKey} already sets {@code down} and {@code chr} does not,
     * so both go through here. An empty modifier list adds nothing, which leaves the message
     * bit-identical to the helper's own output.
     */
    private static Message legacy(Message base, boolean down, List<ControlKey> held) {
        // hbb.MessageOuterClass.KeyEvent cannot be imported under its simple name: this class
        // translates android.view.KeyEvent and imports that one.
        final hbb.MessageOuterClass.KeyEvent.Builder event =
                base.getKeyEvent().toBuilder().setDown(down);
        event.addAllModifiers(held);
        return base.toBuilder().setKeyEvent(event.build()).build();
    }

    /**
     * Returns the control key for a special or modifier Android key code.
     *
     * <p>This is the pure lookup used by {@link #map(KeyEvent)} and is public so the
     * table can be tested without constructing an Android event.
     *
     * <p>Left and right modifiers share one enum value because the protocol has no
     * left/right distinction for Shift, Control and Alt; Meta is used for both Meta
     * keys. See {@code InputMessages.controlKeyMap()} for the full protocol name list.
     *
     * @param androidKeyCode an {@code android.view.KeyEvent.KEYCODE_*} value
     * @return the control key, or null when this code has no special mapping
     */
    public static ControlKey controlKeyFor(int androidKeyCode) {
        switch (androidKeyCode) {
            case KeyEvent.KEYCODE_ENTER:
                return ControlKey.Return;
            case KeyEvent.KEYCODE_DEL:
                return ControlKey.Backspace;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return ControlKey.Delete;
            case KeyEvent.KEYCODE_TAB:
                return ControlKey.Tab;
            case KeyEvent.KEYCODE_ESCAPE:
                return ControlKey.Escape;
            case KeyEvent.KEYCODE_DPAD_UP:
                return ControlKey.UpArrow;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return ControlKey.DownArrow;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return ControlKey.LeftArrow;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return ControlKey.RightArrow;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return ControlKey.Home;
            case KeyEvent.KEYCODE_MOVE_END:
                return ControlKey.End;
            case KeyEvent.KEYCODE_PAGE_UP:
                return ControlKey.PageUp;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return ControlKey.PageDown;
            case KeyEvent.KEYCODE_INSERT:
                return ControlKey.Insert;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return ControlKey.CapsLock;
            case KeyEvent.KEYCODE_F1:
                return ControlKey.F1;
            case KeyEvent.KEYCODE_F2:
                return ControlKey.F2;
            case KeyEvent.KEYCODE_F3:
                return ControlKey.F3;
            case KeyEvent.KEYCODE_F4:
                return ControlKey.F4;
            case KeyEvent.KEYCODE_F5:
                return ControlKey.F5;
            case KeyEvent.KEYCODE_F6:
                return ControlKey.F6;
            case KeyEvent.KEYCODE_F7:
                return ControlKey.F7;
            case KeyEvent.KEYCODE_F8:
                return ControlKey.F8;
            case KeyEvent.KEYCODE_F9:
                return ControlKey.F9;
            case KeyEvent.KEYCODE_F10:
                return ControlKey.F10;
            case KeyEvent.KEYCODE_F11:
                return ControlKey.F11;
            case KeyEvent.KEYCODE_F12:
                return ControlKey.F12;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return ControlKey.Shift;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return ControlKey.Control;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return ControlKey.Alt;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                return ControlKey.Meta;
            default:
                return null;
        }
    }

    private static boolean hasShift(int metaState) {
        return (metaState & (KeyEvent.META_SHIFT_ON
                | KeyEvent.META_SHIFT_LEFT_ON
                | KeyEvent.META_SHIFT_RIGHT_ON)) != 0;
    }

    private static boolean hasCtrl(int metaState) {
        return (metaState & (KeyEvent.META_CTRL_ON
                | KeyEvent.META_CTRL_LEFT_ON
                | KeyEvent.META_CTRL_RIGHT_ON)) != 0;
    }

    private static boolean hasAlt(int metaState) {
        return (metaState & (KeyEvent.META_ALT_ON
                | KeyEvent.META_ALT_LEFT_ON
                | KeyEvent.META_ALT_RIGHT_ON)) != 0;
    }

    private static boolean hasMeta(int metaState) {
        return (metaState & (KeyEvent.META_META_ON
                | KeyEvent.META_META_LEFT_ON
                | KeyEvent.META_META_RIGHT_ON)) != 0;
    }
}
