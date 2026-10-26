package com.rustdesk.java.net;

import java.io.Closeable;
import java.io.IOException;

/** A bidirectional channel of framed RustDesk messages. */
public interface Transport extends Closeable {

    /** Sends one payload as a single frame. */
    void send(byte[] payload) throws IOException;

    /** Blocks for the next payload; returns null when the peer closed the stream. */
    byte[] receive() throws IOException;

    @Override
    void close();
}
