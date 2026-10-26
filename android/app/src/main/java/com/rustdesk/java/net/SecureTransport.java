package com.rustdesk.java.net;

import java.io.IOException;

/**
 * Applies the negotiated {@link Cipher} to frame payloads.
 *
 * <p>Only the payload is sealed; the frame header stays readable so the peer can size its read
 * before it owns a key. This mirrors {@code Stream::send_raw}/{@code Stream::next} in tcp.rs.
 */
public class SecureTransport implements Transport {

    private final Transport delegate;
    private final Cipher cipher;

    public SecureTransport(Transport delegate, Cipher cipher) {
        this.delegate = delegate;
        this.cipher = cipher;
    }

    @Override
    public void send(byte[] payload) throws IOException {
        delegate.send(cipher.seal(payload));
    }

    @Override
    public byte[] receive() throws IOException {
        byte[] sealed = delegate.receive();
        if (sealed == null) {
            return null;
        }
        try {
            return cipher.open(sealed);
        } catch (IllegalStateException e) {
            throw new IOException("decryption failed", e);
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
