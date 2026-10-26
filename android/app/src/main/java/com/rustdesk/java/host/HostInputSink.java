package com.rustdesk.java.host;

/**
 * Injects viewer input on the controlled machine.
 *
 * <p>{@link HostSession} must stay free of {@code android.*} so it keeps compiling and running on
 * a plain JVM, and the only injector the app has, {@link InputInjector}, is an accessibility
 * service, i.e. Android-only. The session therefore talks to this interface and the app installs
 * an implementation with {@link HostSession#setInputSink(HostInputSink)}. With no sink installed
 * the session reports every input message and drops it.</p>
 *
 * <p>Both methods return false when the action did not happen. The implementation is expected to
 * log the reason itself; the session only sees the boolean and passes on a short note.</p>
 */
public interface HostInputSink {

    /**
     * Types {@code text}. The accessibility implementation can only set the content of the
     * focused editable field, so this is not a keystroke stream: every call replaces what the
     * field holds, and with no focused field the text ends up on the clipboard instead.
     */
    boolean text(String text);

    /**
     * Sends one key by Android key code ({@code android.view.KeyEvent.KEYCODE_*}). Passed as a
     * plain int so this interface does not pull in the platform.
     */
    boolean key(int keyCode, boolean down);
}
