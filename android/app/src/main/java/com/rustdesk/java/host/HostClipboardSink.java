package com.rustdesk.java.host;

/**
 * Bridges the platform clipboard and the session.
 *
 * <p>{@link HostSession} must stay free of {@code android.*} so it keeps compiling and running on
 * a plain JVM, and the Android clipboard lives behind {@code android.content.ClipboardManager}.
 * The session therefore talks to this interface and the app installs an implementation with
 * {@link HostSession#setClipboardSink(HostClipboardSink)}. With no sink installed clipboard
 * messages from the viewer are ignored and the local clipboard is never read.</p>
 *
 * <p>Both methods are called on the session's own threads. Implementations may block briefly,
 * but they are re-read every couple of seconds while the session runs, so they must not wait on
 * anything slow.</p>
 */
public interface HostClipboardSink {

    /** Writes {@code text} to the local clipboard, replacing its current text content. */
    void setText(String text);

    /** Reads the local clipboard text, or {@code null} when it holds no text. */
    String getText();
}
