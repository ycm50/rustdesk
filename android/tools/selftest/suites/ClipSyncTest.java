// Migrated from A:\Downloads\tmpscripts\clip\ClipSyncTest.java -- JVM-only regression suite, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.session.ClipboardMessages;
import hbb.MessageOuterClass.Clipboard;
import hbb.MessageOuterClass.ClipboardFormat;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.MouseEvent;
import hbb.MessageOuterClass.MultiClipboards;

import com.google.protobuf.ByteString;

public final class ClipSyncTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        Message decoded = roundTrip(ClipboardMessages.text("\u4f60\u597d"));
        check("text survives serialize/parse/readText as \u4f60\u597d",
                "\u4f60\u597d".equals(ClipboardMessages.readText(decoded)));
        check("text uses format Text", decoded.getClipboard().getFormat() == ClipboardFormat.Text);
        check("text is sent uncompressed", !decoded.getClipboard().getCompress());

        Message empty = roundTrip(ClipboardMessages.text(""));
        check("empty text stays a clipboard message", empty.hasClipboard());
        String emptyText = ClipboardMessages.readText(empty);
        check("empty text decodes to empty string, not null", "".equals(emptyText));

        Message mouse = Message.newBuilder()
                .setMouseEvent(MouseEvent.newBuilder().setMask(0).setX(1).setY(2).build())
                .build();
        check("mouseEvent gives null (in memory)", ClipboardMessages.readText(mouse) == null);
        check("mouseEvent gives null (after parse)",
                ClipboardMessages.readText(roundTrip(mouse)) == null);

        Message html = ClipboardMessages.html("<b>hi</b>");
        check("html uses format Html", html.getClipboard().getFormat() == ClipboardFormat.Html);
        check("readText ignores non-Text clipboards", ClipboardMessages.readText(html) == null);

        Message multi = Message.newBuilder()
                .setMultiClipboards(MultiClipboards.newBuilder()
                        .addClipboards(html.getClipboard())
                        .addClipboards(ClipboardMessages.text("\u591a").getClipboard())
                        .build())
                .build();
        check("multi_clipboards picks the first Text entry",
                "\u591a".equals(ClipboardMessages.readText(roundTrip(multi))));

        Message compressed = Message.newBuilder()
                .setClipboard(Clipboard.newBuilder()
                        .setCompress(true)
                        .setFormat(ClipboardFormat.Text)
                        .setContent(ByteString.copyFrom(new byte[] {1, 2, 3}))
                        .build())
                .build();
        boolean threw = false;
        try {
            ClipboardMessages.readText(compressed);
        } catch (UnsupportedOperationException expected) {
            threw = true;
        }
        check("compressed text throws UnsupportedOperationException", threw);

        System.out.println();
        if (failed == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: FAILED (" + failed + " failed, " + passed + " passed)");
            System.exit(1);
        }
    }

    private static Message roundTrip(Message message) throws Exception {
        return Message.parseFrom(message.toByteArray());
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
