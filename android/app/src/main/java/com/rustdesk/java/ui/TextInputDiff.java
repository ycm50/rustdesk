package com.rustdesk.java.ui;

/**
 * Turns "the local field now reads this" into the keystrokes the peer is missing.
 *
 * <p>Phone IMEs rewrite their whole composing run on every revision — speech recognition does it
 * on every recognised word — so forwarding the changed range verbatim repeats text the peer
 * already holds. The difference is taken against what was already sent instead: the shared prefix
 * and suffix stay untouched, and the peer gets backspaces for the remainder of the old text
 * followed by the new middle.
 */
public final class TextInputDiff {

    /** Keys to delete at the peer's cursor before {@link #insert} is typed. */
    public final int backspaces;

    /** Replacement text for the deleted keys; never null. */
    public final String insert;

    private TextInputDiff(int backspaces, String insert) {
        this.backspaces = backspaces;
        this.insert = insert;
    }

    public static TextInputDiff between(String sent, String current) {
        String previous = sent == null ? "" : sent;
        String next = current == null ? "" : current;
        int prefix = 0;
        int maxPrefix = Math.min(previous.length(), next.length());
        while (prefix < maxPrefix && previous.charAt(prefix) == next.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < previous.length() - prefix
                && suffix < next.length() - prefix
                && previous.charAt(previous.length() - 1 - suffix)
                        == next.charAt(next.length() - 1 - suffix)) {
            suffix++;
        }
        return new TextInputDiff(previous.length() - prefix - suffix,
                next.substring(prefix, next.length() - suffix));
    }

    /** True when the peer already holds this text, so nothing has to be sent. */
    public boolean isEmpty() {
        return backspaces == 0 && insert.isEmpty();
    }

    @Override
    public String toString() {
        return "TextInputDiff{backspaces=" + backspaces + ", insert='" + insert + "'}";
    }
}
