// JVM-only regression suite for ui.TextInputDiff, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.ui.TextInputDiff;

/**
 * Pins the one reported failure: phone speech input rewrites its whole composing run on every
 * recognised word, so forwarding the changed range typed every revision again
 * ("我是卖货我是卖货机我是卖货机想…"). The peer's text is modelled here and must end up being the
 * last revision.
 */
public final class TextInputDiffTest {

    private static final String GREETING = "\u4f60\u597d\uff0c";
    private static final String GOODS = GREETING + "\u6211\u662f\u5356\u8d27";
    private static final String GOODS_MACHINE = GOODS + "\u673a";
    private static final String GOODS_MACHINE_THINK = GOODS_MACHINE + "\u60f3";
    private static final String GOODS_MACHINE_THINK_ROOM = GOODS_MACHINE_THINK + "\u623f";
    private static final String FINAL =
            GREETING + "\u6211\u662f\u5356\u8d27\u7684\u5c0f\u884c\u5bb6";

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("TextInputDiff (pure JVM, no android.jar)");
        System.out.println();

        check(replay(GREETING, GOODS, GOODS_MACHINE, GOODS_MACHINE_THINK,
                GOODS_MACHINE_THINK_ROOM, FINAL).equals(FINAL),
                "语音逐词修订后，对端文本就是最后一次修订，不重复");

        check(replay(GOODS_MACHINE_THINK_ROOM, FINAL).equals(FINAL),
                "末尾被修正时，先删掉识别错的尾巴再补正确内容");

        check(replay("a", "ab", "abc").equals("abc"),
                "普通逐字输入仍然一次一个字符");

        System.out.println();
        System.out.println("checks: " + checks);
        if (failures == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: FAILED (" + failures + " of " + checks + " checks)");
            System.exit(1);
        }
    }

    /** Applies every revision to a model of the peer's text field. */
    private static String replay(String... revisions) {
        String peer = "";
        String sent = "";
        for (String revision : revisions) {
            TextInputDiff diff = TextInputDiff.between(sent, revision);
            sent = revision;
            peer = peer.substring(0, peer.length() - diff.backspaces) + diff.insert;
        }
        return peer;
    }

    private static void check(boolean ok, String description) {
        checks++;
        if (ok) {
            System.out.println("[PASS] " + description);
        } else {
            failures++;
            System.out.println("[FAIL] " + description);
        }
    }
}
