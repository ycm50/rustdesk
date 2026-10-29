package com.rustdesk.java.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * RustDesk frame format (libs/hbb_common/src/bytes_codec.rs).
 *
 * <p>The header packs the payload length together with its own byte count into the low two bits:
 * {@code value = (len << 2) | tag}, where {@code tag = headerBytes - 1}. Header bytes are written
 * little endian, so the tag in byte 0 tells the reader how many bytes to consume.
 */
public final class FrameCodec {

    public static final int MAX_FRAME_LENGTH = 0x3FFF_FFFF;

    private FrameCodec() {}

    /** Header size a given payload length needs. */
    public static int headerSize(int len) {
        if (len <= 0x3F) return 1;
        if (len <= 0x3FFF) return 2;
        if (len <= 0x3F_FFFF) return 3;
        return 4;
    }

    public static byte[] encodeHeader(int len) {
        if (len < 0 || len > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("payload too large: " + len);
        }
        int n = headerSize(len);
        long value = ((long) len << 2) | (n - 1);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        return out;
    }

    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        byte[] header = encodeHeader(payload.length);
        out.write(header);
        out.write(payload);
        out.flush();
    }

    /** Reads exactly one frame; returns null on a clean end of stream before any header byte. */
    public static byte[] readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int headerLen = (b0 & 0x3) + 1;
        long value = b0 & 0xFF;
        for (int i = 1; i < headerLen; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("truncated frame header");
            }
            value |= ((long) (b & 0xFF)) << (8 * i);
        }
        int len = (int) (value >>> 2);
        if (len < 0 || len > MAX_FRAME_LENGTH) {
            throw new IOException("bad frame length " + len);
        }
        return readFully(in, len);
    }

    public static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) {
                throw new EOFException("truncated frame payload: " + off + "/" + len);
            }
            off += r;
        }
        return buf;
    }
}
