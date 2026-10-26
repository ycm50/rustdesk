package com.rustdesk.java.net;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Carries the RustDesk stream over a WebSocket instead of a raw socket.
 *
 * <p>RustDesk peers accept this: a connection that opens with an {@code Upgrade: websocket}
 * request is wrapped by the host into its own WebSocket stream, so the payload framing is unchanged
 * and only the transport differs. That is what makes a Cloudflare tunnel usable, because the
 * tunnel terminates TLS and can only forward HTTP-style traffic.
 */
public final class WsTransport implements Transport {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("RDJAVA");

    /** One message, or the end of the stream. */
    private static final class Frame {
        final byte[] payload;
        final boolean closed;

        Frame(byte[] payload, boolean closed) {
            this.payload = payload;
            this.closed = closed;
        }
    }

    private final LinkedBlockingQueue<Frame> incoming = new LinkedBlockingQueue<>();
    private final CountDownLatch handshake = new CountDownLatch(1);
    private volatile WebSocket socket;
    private volatile boolean opened;
    private volatile boolean closed;

    public WsTransport(String url) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(url).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                opened = true;
                handshake.countDown();
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                incoming.offer(new Frame(bytes.toByteArray(), false));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                // The peer sends binary only; ignore anything else instead of corrupting the stream.
                LOG.fine("ws: ignoring a text frame of " + text.length() + " chars");
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                closed = true;
                handshake.countDown();
                incoming.offer(new Frame(null, true));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                LOG.log(java.util.logging.Level.WARNING, "ws failed", t);
                closed = true;
                handshake.countDown();
                incoming.offer(new Frame(null, true));
            }
        });

        // newWebSocket returns immediately and send() would queue bytes before the handshake, so
        // the caller must not start framing until onOpen or onFailure has fired.
        boolean finished;
        try {
            finished = handshake.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while opening the websocket", e);
        }
        if (!finished || !opened) {
            throw new IOException("websocket did not open: " + url);
        }
        LOG.info("ws open: " + url);
    }

    @Override
    public void send(byte[] payload) throws IOException {
        WebSocket current = socket;
        if (current == null || closed) {
            throw new IOException("websocket closed");
        }
        if (!current.send(ByteString.of(payload))) {
            throw new IOException("websocket rejected the payload");
        }
    }

    @Override
    public byte[] receive() throws IOException {
        while (true) {
            Frame frame;
            try {
                frame = incoming.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            if (frame.closed) {
                return null;
            }
            if (frame.payload.length > 0) {
                return frame.payload;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        WebSocket current = socket;
        if (current != null) {
            current.close(1000, null);
        }
        incoming.offer(new Frame(null, true));
    }
}

