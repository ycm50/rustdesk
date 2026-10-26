package com.rustdesk.java.host;

import android.util.Log;

/**
 * The Android {@link HostInputSink}: forwards to the connected {@link InputInjector}
 * accessibility service.
 *
 * <p>This is the file that carries the {@code android.*} dependency, so {@link HostSession} and
 * {@link HostInputSink} stay compilable without {@code android.jar}. The host wiring installs it
 * with {@code session.setInputSink(InputInjectorSink.INSTANCE)}.</p>
 *
 * <p>Without the accessibility service there is nothing to inject through, so every call is
 * logged and reported as failed instead of silently disappearing.</p>
 */
public final class InputInjectorSink implements HostInputSink {

    private static final String TAG = "InputInjectorSink";

    /** Stateless, so one shared instance is enough. */
    public static final InputInjectorSink INSTANCE = new InputInjectorSink();

    private InputInjectorSink() {
    }

    @Override
    public boolean text(String text) {
        InputInjector injector = InputInjector.getInstance();
        if (injector == null) {
            Log.i(TAG, "accessibility service is not connected, dropping "
                    + (text == null ? 0 : text.length()) + " character(s)");
            return false;
        }
        return injector.text(text);
    }

    @Override
    public boolean key(int keyCode, boolean down) {
        InputInjector injector = InputInjector.getInstance();
        if (injector == null) {
            Log.i(TAG, "accessibility service is not connected, dropping key " + keyCode);
            return false;
        }
        return injector.key(keyCode, down);
    }
}
