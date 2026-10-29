package com.rustdesk.java.session;

import com.google.protobuf.ByteString;
import hbb.MessageOuterClass.Clipboard;
import hbb.MessageOuterClass.ClipboardFormat;
import hbb.MessageOuterClass.Message;

import java.nio.charset.StandardCharsets;

/**
 * Builds and parses clipboard messages (message.proto: {@code Clipboard}, carried in
 * {@code Message.clipboard = 16}).
 *
 * <p>Text is the UTF-8 bytes of the string in {@code content} with {@code format = Text};
 * the reference host decodes it the same way (src/clipboard.rs:from_clipboard). An empty
 * text clipboard is still a clipboard message and decodes to {@code ""}, while {@code null}
 * means the message carries no text clipboard at all.
 */
public final class ClipboardMessages {

    private ClipboardMessages() {}

    public static Message text(String text) {
        return plain(text, ClipboardFormat.Text);
    }

    public static Message html(String html) {
        return plain(html, ClipboardFormat.Html);
    }

    private static Message plain(String value, ClipboardFormat format) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        // Uncompressed on purpose: the host only zstd-decompresses when compress is true
        // (src/clipboard.rs:from_clipboard), and zstd is not available in this pure-Java client.
        Clipboard clipboard = Clipboard.newBuilder()
                .setCompress(false)
                .setFormat(format)
                .setContent(ByteString.copyFrom(value.getBytes(StandardCharsets.UTF_8)))
                .build();
        return Message.newBuilder().setClipboard(clipboard).build();
    }

    /**
     * Returns the text of a clipboard message, or {@code null} when the message carries none.
     * Accepted shapes are a single {@code clipboard} and {@code multi_clipboards}, where the
     * first Text entry wins.
     */
    public static String readText(Message message) {
        if (message == null) {
            return null;
        }
        if (message.hasClipboard()) {
            return textOf(message.getClipboard());
        }
        if (message.hasMultiClipboards()) {
            for (Clipboard clipboard : message.getMultiClipboards().getClipboardsList()) {
                String text = textOf(clipboard);
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String textOf(Clipboard clipboard) {
        if (clipboard.getFormat() != ClipboardFormat.Text) {
            return null;
        }
        if (clipboard.getCompress()) {
            // The host zstd-compresses any payload that shrinks (src/clipboard.rs:plain_to_proto),
            // so plain text from a peer can arrive compressed.
            throw new UnsupportedOperationException(
                    "compressed clipboard content needs zstd, which this client does not ship");
        }
        return new String(clipboard.getContent().toByteArray(), StandardCharsets.UTF_8);
    }
}
