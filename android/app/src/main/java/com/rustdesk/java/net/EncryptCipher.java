package com.rustdesk.java.net;

import com.rustdesk.java.crypto.Encrypt;
import com.rustdesk.java.crypto.Sodium;

/** Bridges the negotiated {@link Encrypt} stream cipher to the transport layer. */
public class EncryptCipher implements Cipher {

    private final Encrypt encrypt;

    public EncryptCipher(Encrypt encrypt) {
        this.encrypt = encrypt;
    }

    @Override
    public byte[] seal(byte[] plaintext) {
        return encrypt.enc(plaintext);
    }

    @Override
    public byte[] open(byte[] ciphertext) {
        try {
            return encrypt.dec(ciphertext);
        } catch (Sodium.DecryptionException e) {
            throw new IllegalStateException("payload authentication failed", e);
        }
    }
}
