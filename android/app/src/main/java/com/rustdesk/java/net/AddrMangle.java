package com.rustdesk.java.net;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Decodes the {@code socket_addr} byte blobs the rendezvous server sends.
 *
 * <p>IPv4 addresses are packed into 128 bits with two obfuscating offsets; see
 * hbb_common/src/lib.rs:AddrMangle. Anything longer than 16 bytes is a raw IPv6 address plus a
 * little endian port.
 */
public final class AddrMangle {

    private static final BigInteger MASK_32 = BigInteger.valueOf(0xFFFFFFFFL);
    private static final BigInteger MASK_24 = BigInteger.valueOf(0xFFFFFFL);
    private static final BigInteger MASK_16 = BigInteger.valueOf(0xFFFFL);

    private AddrMangle() {}

    public static InetSocketAddress decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length > 16) {
            if (bytes.length != 18) {
                return null;
            }
            byte[] ip = new byte[16];
            System.arraycopy(bytes, 0, ip, 0, 16);
            int port = (bytes[16] & 0xFF) | ((bytes[17] & 0xFF) << 8);
            try {
                return new InetSocketAddress(InetAddress.getByAddress(ip), port);
            } catch (Exception e) {
                return null;
            }
        }

        byte[] padded = new byte[16];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        // BigInteger(byte[]) is big endian, so reverse to get the little endian u128.
        byte[] be = new byte[16];
        for (int i = 0; i < 16; i++) {
            be[i] = padded[15 - i];
        }
        BigInteger number = new BigInteger(1, be);

        BigInteger tm = number.shiftRight(17).and(MASK_32);
        long ipRaw = number.shiftRight(49).subtract(tm).longValue() & 0xFFFFFFFFL;
        // Rust computes this in u32 and truncates to u16 when building the address, so the
        // subtraction is allowed to wrap. Without the mask a wrapped value turns negative here and
        // InetSocketAddress rejects it, which is why some short (11 byte) blobs failed to decode.
        long port = (number.and(MASK_24).longValue() - tm.and(MASK_16).longValue()) & 0xFFFFL;

        byte[] ip = new byte[] {
                (byte) (ipRaw & 0xFF),
                (byte) ((ipRaw >>> 8) & 0xFF),
                (byte) ((ipRaw >>> 16) & 0xFF),
                (byte) ((ipRaw >>> 24) & 0xFF),
        };
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), (int) port);
        } catch (Exception e) {
            return null;
        }
    }
}
