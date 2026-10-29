package com.rustdesk.java.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** Frame transport over a plain TCP or TLS socket. */
public class StreamTransport implements Transport {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public StreamTransport(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
    }

    @Override
    public synchronized void send(byte[] payload) throws IOException {
        FrameCodec.writeFrame(out, payload);
    }

    @Override
    public byte[] receive() throws IOException {
        return FrameCodec.readFrame(in);
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }
}
