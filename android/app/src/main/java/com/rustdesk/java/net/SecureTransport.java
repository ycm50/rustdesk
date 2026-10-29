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
    public synchronized void send(byte[] payload) throws IOException {
        // seal() advances the nonce counter, so encrypting and handing the frame to the delegate
        // must be one atomic step; otherwise concurrent callers emit frames with out-of-order
        // nonces and the peer drops the connection.
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
