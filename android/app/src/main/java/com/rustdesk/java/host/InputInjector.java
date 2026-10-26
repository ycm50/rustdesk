package com.rustdesk.java.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Host side input injection built on an AccessibilityService.
 *
 * <p>Needs to be declared by the manifest (not part of this change) as
 * {@code <service android:name=".host.InputInjector" android:exported="false"
 * android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">} with the
 * {@code android.accessibilityservice.AccessibilityService} intent filter and a
 * {@code meta-data android:name="android.accessibilityservice"} pointing at a config XML that
 * enables the gesture capability.
 *
 * <p>Absolute pointer moves and key events are not reachable through this API: those need
 * {@code InputManager.injectInputEvent}, a hidden call guarded by the signature level INJECT_EVENTS
 * permission. A production implementation should inject through root or Instrumentation; the
 * gesture approximations below are the best an ordinary app can do.
 */
public class InputInjector extends AccessibilityService {

    private static final String TAG = "InputInjector";

    /** Long enough for the system to classify the stroke as a tap, short enough to stay a click. */
    private static final long TAP_MS = 40L;

    /** Right click has no gesture equivalent, so it is mapped to the context-menu long press. */
    private static final long LONG_PRESS_MS = 600L;

    private static volatile InputInjector instance;

    public static InputInjector getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    public boolean mouseMove(int x, int y) {
        // dispatchGesture has no hover and no absolute pointer: a 1 px stroke is the closest a move
        // gets, and it reaches the peer as a very short drag.
        return dispatch(pathAt(x, y), 1L);
    }

    /**
     * A press and a release cannot be expressed as two gestures, so the whole click is dispatched on
     * {@code down} and {@code up} does nothing.
     *
     * <p>Button bits follow the RustDesk mask: 1 = left, 2 = right, 4 = middle.
     */
    public boolean mouseButton(int x, int y, int buttonMask, boolean down) {
        if (!down) return true;
        if ((buttonMask & 2) != 0) {
            return dispatch(pathAt(x, y), LONG_PRESS_MS);
        }
        if ((buttonMask & 4) != 0) {
            Log.w(TAG, "middle click has no accessibility equivalent");
            return false;
        }
        return dispatch(pathAt(x, y), TAP_MS);
    }

    /**
     * Writes into the focused editable node. When no such node exists the text is put on the
     * clipboard instead, which the user can paste by hand; accessibility cannot paste on its own
     * without a focused node either.
     */
    @SuppressWarnings("deprecation")
    public boolean text(String s) {
        if (s == null || s.isEmpty()) return true;
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null) {
            try {
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
                return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            } finally {
                // Deprecated from API 33, but it still releases the node handle on 24..32.
                focus.recycle();
            }
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("RustDesk", s));
        return true;
    }

    /**
     * Only global actions can be mapped; arbitrary key codes would have to be injected as input
     * events, which accessibility does not allow. There is no key-up channel, so {@code down ==
     * false} is a no-op.
     */
    public boolean key(int keyCode, boolean down) {
        if (!down) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case KeyEvent.KEYCODE_HOME:
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case KeyEvent.KEYCODE_APP_SWITCH:
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case KeyEvent.KEYCODE_NOTIFICATION:
                return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default:
                Log.w(TAG, "key " + keyCode + " cannot be injected through accessibility");
                return false;
        }
    }

    /**
     * A path of a single point counts as empty and is rejected by GestureDescription, so every
     * stroke carries a 1 px segment, well inside the touch slop of a tap.
     */
    private static Path pathAt(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x + 1, y);
        return path;
    }

    private boolean dispatch(Path path, long durationMs) {
        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs))
                    .build();
            return dispatchGesture(gesture, null, null);
        } catch (RuntimeException e) {
            // Paths outside the display and a not yet connected service are rejected here.
            Log.w(TAG, "dispatchGesture failed: " + e.getMessage());
            return false;
        }
    }
}
