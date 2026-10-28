package com.rustdesk.java.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import com.rustdesk.java.session.InputMessages;
import com.rustdesk.java.session.ScanCodes;

import java.util.ArrayList;
import java.util.List;

import hbb.MessageOuterClass.ControlKey;
import hbb.MessageOuterClass.Message;

/**
 * A full keyboard drawn on one canvas, with real multi-touch support.
 *
 * <p>Buttons cannot do this: the layout hands each touch to one child, so holding Shift while
 * tapping a letter is impossible. Here every pointer is tracked separately against the key grid,
 * which makes combinations like Shift+A, Ctrl+Alt+Del or two arrows at once work the way a
 * physical keyboard does.
 */
public final class KeyboardView extends View {

    /** Receives press and release events for individual keys. */
    public interface Sender {
        void send(Message message);
    }

    private static final int KIND_CONTROL = 0;
    private static final int KIND_CHAR = 1;

    private static final class Key {
        final String label;
        final int kind;
        final ControlKey control;
        final char chr;
        final float weight;
        final RectF bounds = new RectF();

        Key(String label, ControlKey control, float weight) {
            this.label = label;
            this.kind = KIND_CONTROL;
            this.control = control;
            this.chr = 0;
            this.weight = weight;
        }

        Key(String label, char chr, float weight) {
            this.label = label;
            this.kind = KIND_CHAR;
            this.control = ControlKey.Control;   // unused for chars
            this.chr = chr;
            this.weight = weight;
        }
    }

    private final Sender sender;
    private final boolean physicalKeys;
    private final List<Key[]> rows = new ArrayList<>();
    /** Keys we pressed a synthetic Shift for, so its release is sent back with the key. */
    private final List<Key> shiftedByUs = new ArrayList<>();
    /** Keys whose release must send nothing because a chord replaced them (Ctrl+Alt+Del). */
    private final List<Key> swallowed = new ArrayList<>();
    private final SparseArray<Key> heldByPointer = new SparseArray<>();
    /** Key released in the last moment, kept highlighted so a quick tap is still visible. */
    private Key recentlyReleased;
    private long recentlyReleasedAt;
    private static final long FLASH_MS = 130L;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public KeyboardView(Context context, Sender sender) {
        this(context, sender, true);
    }

    /**
     * @param physicalKeys true when the peer takes Windows scan codes (Map mode). The chatty
     *     alternative types one character per message and cannot hold a key down, so chords and
     *     held keys only work through this path.
     */
    public KeyboardView(Context context, Sender sender, boolean physicalKeys) {
        super(context);
        this.sender = sender;
        this.physicalKeys = physicalKeys;
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        buildRows();
    }

    // ------------------------------------------------------------------ layout

    private void buildRows() {
        rows.add(new Key[] {
                key("F1", ControlKey.F1), key("F2", ControlKey.F2), key("F3", ControlKey.F3),
                key("F4", ControlKey.F4), key("F5", ControlKey.F5), key("F6", ControlKey.F6),
                key("F7", ControlKey.F7), key("F8", ControlKey.F8), key("F9", ControlKey.F9),
                key("F10", ControlKey.F10), key("F11", ControlKey.F11), key("F12", ControlKey.F12),
        });
        rows.add(new Key[] {
                chr('`'), chr('1'), chr('2'), chr('3'), chr('4'), chr('5'), chr('6'), chr('7'),
                chr('8'), chr('9'), chr('0'), chr('-'), chr('='),
                key("Back", ControlKey.Backspace, 1.8f),
        });
        rows.add(new Key[] {
                key("Tab", ControlKey.Tab, 1.4f),
                chr('q'), chr('w'), chr('e'), chr('r'), chr('t'), chr('y'), chr('u'), chr('i'),
                chr('o'), chr('p'), chr('['), chr(']'), chr('\\'),
        });
        rows.add(new Key[] {
                key("Caps", ControlKey.CapsLock, 1.6f),
                chr('a'), chr('s'), chr('d'), chr('f'), chr('g'), chr('h'), chr('j'), chr('k'),
                chr('l'), chr(';'), chr('\''), key("Enter", ControlKey.Return, 1.8f),
        });
        rows.add(new Key[] {
                key("Shift", ControlKey.Shift, 2f),
                chr('z'), chr('x'), chr('c'), chr('v'), chr('b'), chr('n'), chr('m'), chr(','),
                chr('.'), chr('/'),
                key("Shift", ControlKey.Shift, 2f),
        });
        rows.add(new Key[] {
                key("Ctrl", ControlKey.Control, 1.3f), key("Win", ControlKey.Meta, 1.3f),
                key("Alt", ControlKey.Alt, 1.3f),
                key("Space", ControlKey.Space, 4f),
                key("Alt", ControlKey.Alt, 1.3f), key("Ctrl", ControlKey.Control, 1.3f),
                key("Esc", ControlKey.Escape, 1.3f), key("Del", ControlKey.Delete, 1.3f),
                key("\u2190", ControlKey.LeftArrow, 1f), key("\u2191", ControlKey.UpArrow, 1f),
                key("\u2193", ControlKey.DownArrow, 1f), key("\u2192", ControlKey.RightArrow, 1f),
        });
    }

    private static Key key(String label, ControlKey control) {
        return new Key(label, control, 1f);
    }

    private static Key key(String label, ControlKey control, float weight) {
        return new Key(label, control, weight);
    }

    private static Key chr(char value) {
        return new Key(String.valueOf(value), value, 1f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutKeys(w, h);
    }

    private void layoutKeys(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float rowHeight = (float) h / rows.size();
        float gap = Math.max(1f, w * 0.0015f);
        for (int r = 0; r < rows.size(); r++) {
            Key[] row = rows.get(r);
            float total = 0f;
            for (Key k : row) total += k.weight;
            float x = 0f;
            float top = r * rowHeight;
            for (Key k : row) {
                float width = w * (k.weight / total);
                k.bounds.set(x + gap, top + gap, x + width - gap, top + rowHeight - gap);
                x += width;
            }
        }
        text.setTextSize(Math.max(10f, rowHeight * 0.34f));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = Math.max(3f, getWidth() * 0.004f);
        for (Key[] row : rows) {
            for (Key k : row) {
                fill.setColor(isHeld(k) ? 0xFF2E7DD1 : 0xFF343434);
                canvas.drawRoundRect(k.bounds, radius, radius, fill);
                stroke.setColor(0xFF6A6A6A);
                stroke.setStrokeWidth(Math.max(1f, getWidth() * 0.0008f));
                canvas.drawRoundRect(k.bounds, radius, radius, stroke);
                text.setColor(0xFFFFFFFF);
                float cy = k.bounds.centerY() - (text.descent() + text.ascent()) / 2f;
                canvas.drawText(k.label, k.bounds.centerX(), cy, text);
            }
        }
    }

    private boolean isHeld(Key key) {
        for (int i = 0; i < heldByPointer.size(); i++) {
            if (heldByPointer.valueAt(i) == key) return true;
        }
        return key == recentlyReleased
                && System.currentTimeMillis() - recentlyReleasedAt < FLASH_MS;
    }

    // ------------------------------------------------------------------ multi touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                press(event.getPointerId(index), event.getX(index), event.getY(index));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                // A finger that slides onto another key should not silently retarget; only release
                // when it leaves its own key, which keeps chords stable under a wobbling thumb.
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    Key held = heldByPointer.get(id);
                    if (held != null && !held.bounds.contains(event.getX(i), event.getY(i))) {
                        releasePointer(id);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                releasePointer(event.getPointerId(event.getActionIndex()));
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                for (int i = heldByPointer.size() - 1; i >= 0; i--) {
                    releasePointer(heldByPointer.keyAt(i));
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void press(int pointerId, float x, float y) {
        if (heldByPointer.get(pointerId) != null) return;
        for (Key[] row : rows) {
            for (Key k : row) {
                if (k.bounds.contains(x, y)) {
                    heldByPointer.put(pointerId, k);
                    sendDown(k);
                    invalidate();
                    return;
                }
            }
        }
    }

    private void releasePointer(int pointerId) {
        Key held = heldByPointer.get(pointerId);
        if (held == null) return;
        heldByPointer.remove(pointerId);
        sendUp(held);
        recentlyReleased = held;
        recentlyReleasedAt = System.currentTimeMillis();
        invalidate();
        postInvalidateDelayed(FLASH_MS + 20);
    }

    private void sendDown(Key k) {
        if (sender == null) return;
        if (k.kind == KIND_CONTROL) {
            // Windows never lets injected input raise the secure attention sequence, so the peer
            // has to ask its own service for it. Recognise the chord here: sending the three keys
            // separately would only deliver a plain Delete.
            if (k.control == ControlKey.Delete && holdsModifier(ControlKey.Control)
                    && holdsModifier(ControlKey.Alt)) {
                sender.send(InputMessages.controlKeyPress(ControlKey.CtrlAltDel));
                swallowed.add(k);
                return;
            }
            sender.send(InputMessages.controlKey(k.control, true, heldModifiers(k)));
            return;
        }
        int code = physicalKeys ? ScanCodes.of(k.chr) : 0;
        if (code == 0) {
            sender.send(InputMessages.unicode(k.chr));
            return;
        }
        // An uppercase letter or a shifted symbol needs Shift held on the peer, and the user is
        // not holding it here: press it for the lifetime of this key.
        boolean shift = ScanCodes.needsShift(k.chr) && !isShiftHeld();
        java.util.List<ControlKey> held = heldModifiers(k);
        if (shift) {
            sender.send(InputMessages.controlKey(ControlKey.Shift, true, held));
            shiftedByUs.add(k);
            held.add(ControlKey.Shift);
        }
        sender.send(InputMessages.keyPosition(code, true, held));
    }

    private void sendUp(Key k) {
        if (sender == null) return;
        if (swallowed.remove(k)) {
            return;
        }
        if (k.kind == KIND_CONTROL) {
            sender.send(InputMessages.controlKey(k.control, false, heldModifiers(k)));
            return;
        }
        int code = physicalKeys ? ScanCodes.of(k.chr) : 0;
        if (code == 0) return;
        sender.send(InputMessages.keyPosition(code, false, heldModifiers(k)));
        if (shiftedByUs.remove(k)) {
            sender.send(InputMessages.controlKey(ControlKey.Shift, false, heldModifiers(k)));
        }
    }

    private boolean holdsModifier(ControlKey wanted) {
        for (int i = 0; i < heldByPointer.size(); i++) {
            Key held = heldByPointer.valueAt(i);
            if (held != null && held.kind == KIND_CONTROL && held.control == wanted) {
                return true;
            }
        }
        return false;
    }

    /**
     * The modifiers currently held by other fingers.
     *
     * <p>The host drops any modifier that a key-down event does not list (it calls
     * {@code release_unpressed_modifiers} first), so every event has to carry the whole set,
     * otherwise pressing Shift would release a held Win key and no chord could ever form.
     */
    private java.util.List<ControlKey> heldModifiers(Key exclude) {
        java.util.List<ControlKey> held = new java.util.ArrayList<>();
        for (int i = 0; i < heldByPointer.size(); i++) {
            Key other = heldByPointer.valueAt(i);
            if (other == null || other == exclude || other.kind != KIND_CONTROL) continue;
            switch (other.control) {
                case Shift:
                case Control:
                case Alt:
                case Meta:
                    held.add(other.control);
                    break;
                default:
                    break;
            }
        }
        return held;
    }

    /** @return true while one of the fingers is on a Shift key */
    private boolean isShiftHeld() {
        for (int i = 0; i < heldByPointer.size(); i++) {
            Key held = heldByPointer.valueAt(i);
            if (held != null && held.kind == KIND_CONTROL && held.control == ControlKey.Shift) {
                return true;
            }
        }
        return false;
    }
}

