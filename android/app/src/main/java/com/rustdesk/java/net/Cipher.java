package com.rustdesk.java.net;

/** Symmetric payload cipher used once the key exchange completed. */
public interface Cipher {

    byte[] seal(byte[] plaintext);

    /** Throws {@link IllegalStateException} when authentication fails. */
    byte[] open(byte[] ciphertext);
}
