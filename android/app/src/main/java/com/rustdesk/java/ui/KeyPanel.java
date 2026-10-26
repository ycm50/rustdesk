package com.rustdesk.java.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rustdesk.java.session.InputMessages;

import hbb.MessageOuterClass.ControlKey;
import hbb.MessageOuterClass.Message;

/**
 * The on-screen key panel: a draggable strip that can show either common shortcuts (Ctrl+C and
 * friends) or a full keyboard, with a close button in its title row.
 *
 * <p>Keys are sent immediately; modifiers are held for the duration of one combination, because a
 * phone has no way to hold Ctrl while tapping a letter.
 */
public final class KeyPanel extends LinearLayout {

    private static final String TAG = "RDJAVA";

    /** What the panel sends when a key is pressed. */
    public interface Sender {
        void send(Message message);
    }

    public interface OnCloseListener {
        void onClosed();
    }

    private final Sender sender;
    private OnCloseListener closeListener;
    private final TextView title;

    public KeyPanel(Context context, Sender sender) {
        super(context);
        this.sender = sender;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF101010);
        int pad = dp(6);
        setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setText("KEYS");
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        Button close = new Button(context);
        close.setText("X");
        close.setTextSize(13);
        close.setTextColor(0xFFFFFFFF);
        close.setBackgroundColor(0xFF8B1E1E);
        close.setPadding(dp(10), 0, dp(10), 0);
        close.setOnClickListener(v -> {
            if (closeListener != null) {
                closeListener.onClosed();
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // The title row doubles as the drag handle; moving the whole panel keeps the video
        // underneath reachable, which matters on a phone screen.
        header.setOnTouchListener(new DragHandle());
        addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** @param mode "Shortcut" for the combination grid, anything else for the full keyboard */
    public void showMode(String mode, boolean fullKeyboard) {
        // Drop everything below the header, then rebuild for the requested mode.
        while (getChildCount() > 1) {
            removeViewAt(1);
        }
        setTranslationX(0f);
        setTranslationY(0f);
        if (fullKeyboard) {
            title.setText("KEYBOARD");
            addView(new KeyboardView(getContext(), sender::send),
                    new LayoutParams(LayoutParams.MATCH_PARENT, dp(190)));
        } else {
            title.setText("SHORTCUTS");
            buildShortcuts();
        }
    }

    public void setOnCloseListener(OnCloseListener listener) {
        this.closeListener = listener;
    }

    // ------------------------------------------------------------------ layouts

    private void buildShortcuts() {
        addRow(new String[] { "Ctrl+C", "Ctrl+V", "Ctrl+X", "Ctrl+Z" },
                new String[] { "c", "v", "x", "z" });
        addRow(new String[] { "Ctrl+A", "Ctrl+S", "Ctrl+F", "Ctrl+W" },
                new String[] { "a", "s", "f", "w" });
        addRow(new String[] { "Ctrl+Z+", "Alt+Tab", "Esc", "Enter" },
                new String[] { "y", null, null, null });
        addRow(new String[] { "Tab", "Win+D", "Ctrl+Shift+Esc", "F5" }, new String[] { null, "d", null, null });
    }

    private void buildFullKeyboard() {
        // Function row.
        String[] fkeys = { "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12" };
        ControlKey[] fmap = { ControlKey.F1, ControlKey.F2, ControlKey.F3, ControlKey.F4, ControlKey.F5,
                ControlKey.F6, ControlKey.F7, ControlKey.F8, ControlKey.F9, ControlKey.F10,
                ControlKey.F11, ControlKey.F12 };
        LinearLayout fRow = new LinearLayout(getContext());
        fRow.setOrientation(HORIZONTAL);
        for (int i = 0; i < fkeys.length; i++) {
            fRow.addView(controlButton(fkeys[i], fmap[i], 1f));
        }
        addView(fRow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Number row plus the editing keys a phone otherwise lacks.
        String[] numbers = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "Del", "Back" };
        ControlKey[] numberKeys = { null, null, null, null, null, null, null, null, null, null,
                ControlKey.Delete, ControlKey.Backspace };
        addMixedRow(numbers, numberKeys);

        // Letters, shifted and unshifted share the same physical key.
        String[][] letterRows = {
                { "q", "w", "e", "r", "t", "y", "u", "i", "o", "p" },
                { "a", "s", "d", "f", "g", "h", "j", "k", "l", ";" },
                { "z", "x", "c", "v", "b", "n", "m", ",", ".", "/" },
        };
        for (String[] row : letterRows) {
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            for (String key : row) {
                line.addView(charButton(key, 1f));
            }
            addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        // Bottom control cluster: navigation, modifiers and space.
        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(HORIZONTAL);
        bottom.addView(controlButton("Esc", ControlKey.Escape, 1f));
        bottom.addView(controlButton("Tab", ControlKey.Tab, 1f));
        bottom.addView(controlButton("Ctrl", ControlKey.Control, 1f));
        bottom.addView(controlButton("Alt", ControlKey.Alt, 1f));
        bottom.addView(controlButton("Shift", ControlKey.Shift, 1f));
        bottom.addView(controlButton("Win", ControlKey.Meta, 1f));
        bottom.addView(controlButton("Enter", ControlKey.Return, 1.4f));
        bottom.addView(controlButton("Left", ControlKey.LeftArrow, 1f));
        bottom.addView(controlButton("Up", ControlKey.UpArrow, 1f));
        bottom.addView(controlButton("Down", ControlKey.DownArrow, 1f));
        bottom.addView(controlButton("Right", ControlKey.RightArrow, 1f));
        addView(bottom, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ rows

    private void addRow(String[] labels, String[] comboKeys) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            Button button = baseButton(labels[i], 1f);
            final String label = labels[i];
            final String key = comboKeys[i];
            button.setOnClickListener(v -> sendShortcut(label, key));
            row.addView(button);
        }
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void addMixedRow(String[] labels, ControlKey[] controlKeys) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            if (controlKeys[i] == null) {
                row.addView(charButton(labels[i], 1f));
            } else {
                row.addView(controlButton(labels[i], controlKeys[i], 1f));
            }
        }
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private Button charButton(final String label, float weight) {
        Button button = baseButton(label, weight);
        button.setOnClickListener(v -> {
            send(InputMessages.unicode(label.charAt(0)));
        });
        return button;
    }

    private Button controlButton(String label, final ControlKey key, float weight) {
        Button button = baseButton(label, weight);
        button.setOnClickListener(v -> send(InputMessages.controlKeyPress(key)));
        return button;
    }

    private Button baseButton(String label, float weight) {
        Button button = new Button(getContext());
        button.setText(label);
        // White on mid grey and a fixed height: the default button background is light, so white
        // text on top of it was invisible, and wrap_content collapsed the rows into thin bars.
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(12);
        // A flat colour kills the pressed state Android would otherwise draw, and these keys give
        // no other feedback, so an explicit state list keeps the press visible.
        android.graphics.drawable.StateListDrawable background =
                new android.graphics.drawable.StateListDrawable();
        background.addState(new int[] { android.R.attr.state_pressed },
                new android.graphics.drawable.ColorDrawable(0xFF2E7DD1));
        background.addState(new int[] { android.R.attr.state_focused },
                new android.graphics.drawable.ColorDrawable(0xFF4A4A4A));
        background.addState(new int[] { },
                new android.graphics.drawable.ColorDrawable(0xFF3C3C3C));
        button.setBackground(background);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        LayoutParams lp = new LayoutParams(0, dp(34), weight);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        button.setLayoutParams(lp);
        return button;
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends one shortcut. The label decides the modifiers ("Ctrl+Shift+Esc"), and the key either
     * comes from {@code comboKey} (a letter typed with Ctrl held) or from the trailing name.
     */
    private void sendShortcut(String label, String comboKey) {
        String[] parts = label.split("\\+");
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            modifiers |= modifierBit(parts[i]);
        }
        pressModifiers(modifiers, true);
        String key = parts[parts.length - 1];
        if (comboKey != null) {
            send(InputMessages.unicode(comboKey.charAt(0)));
        } else if ("Esc".equals(key)) {
            send(InputMessages.controlKeyPress(ControlKey.Escape));
        } else if ("Enter".equals(key)) {
            send(InputMessages.controlKeyPress(ControlKey.Return));
        } else if ("Tab".equals(key)) {
            send(InputMessages.controlKeyPress(ControlKey.Tab));
        } else if ("F5".equals(key)) {
            send(InputMessages.controlKeyPress(ControlKey.F5));
        } else {
            Log.i(TAG, "shortcut without a mapping: " + label);
        }
        pressModifiers(modifiers, false);
    }

    private static int modifierBit(String name) {
        if ("Ctrl".equals(name)) return 1;
        if ("Alt".equals(name)) return 2;
        if ("Shift".equals(name)) return 4;
        if ("Win".equals(name)) return 8;
        return 0;
    }

    private void pressModifiers(int bits, boolean down) {
        if ((bits & 1) != 0) send(InputMessages.controlKey(ControlKey.Control, down));
        if ((bits & 2) != 0) send(InputMessages.controlKey(ControlKey.Alt, down));
        if ((bits & 4) != 0) send(InputMessages.controlKey(ControlKey.Shift, down));
        if ((bits & 8) != 0) send(InputMessages.controlKey(ControlKey.Meta, down));
    }

    private void send(Message message) {
        if (sender != null) {
            sender.send(message);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Moves the panel when the title row is dragged. */
    private final class DragHandle implements OnTouchListener {
        private float startX;
        private float startY;
        private float originX;
        private float originY;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    originX = getTranslationX();
                    originY = getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    setTranslationX(originX + (event.getRawX() - startX));
                    setTranslationY(originY + (event.getRawY() - startY));
                    return true;
                default:
                    return false;
            }
        }
    }
}







