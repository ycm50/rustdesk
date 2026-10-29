// JVM-only regression suite for net.SecureTransport, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.net.Cipher;
import com.rustdesk.java.net.SecureTransport;
import com.rustdesk.java.net.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pins the ordering contract of {@link SecureTransport}: the cipher counter must advance in the
 * same order as the frames handed to the delegate transport.
 *
 * <p>Two threads seal through one {@link SecureTransport}. The fake cipher stamps each sealed
 * payload with its counter and yields before returning, which widens the window between
 * "counter advanced" and "frame written". The recording transport collects what was actually
 * written. If sealing and writing ever stop being one atomic step, the two sequences diverge
 * and this suite fails.</p>
 */
public final class TransportOrderTest {

    private static final int THREADS = 2;
    private static final int SENDS_PER_THREAD = 200;
    private static final int TOTAL = THREADS * SENDS_PER_THREAD;

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        System.out.println("SecureTransport write order (pure JVM, no android.jar)");
        System.out.println();

        RecordingTransport recording = new RecordingTransport();
        SequenceCipher cipher = new SequenceCipher();
        final SecureTransport transport = new SecureTransport(recording, cipher);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();

        Thread[] threads = new Thread[THREADS];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < SENDS_PER_THREAD; i++) {
                        try {
                            transport.send(new byte[] { 0 });
                        } catch (Throwable e) {
                            error.compareAndSet(null, e);
                            return;
                        }
                    }
                }
            }, "transport-order-" + t);
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10000);
        }

        Throwable failure = error.get();
        check(failure == null, "all " + TOTAL + " send() calls completed"
                + (failure == null ? "" : " -- " + failure));
        for (Thread thread : threads) {
            check(!thread.isAlive(), thread.getName() + " finished within 10s");
        }

        int[] actual = recording.written();
        // The payload is a single byte, so the counter travels modulo 256. Positional comparison
        // still catches any reordering, duplication or lost frame.
        int[] expected = new int[TOTAL];
        AtomicInteger sequence = new AtomicInteger();
        for (int i = 0; i < expected.length; i++) {
            expected[i] = sequence.getAndIncrement() & 0xFF;
        }

        check(actual.length == TOTAL,
                "delegate received " + TOTAL + " frames (got " + actual.length + ")");
        boolean ordered = actual.length == TOTAL;
        if (ordered) {
            for (int i = 0; i < expected.length; i++) {
                if (actual[i] != expected[i]) {
                    ordered = false;
                    break;
                }
            }
        }
        check(ordered, "written cipher order matches the seal order 0.." + (TOTAL - 1));
        check(cipher.count() == TOTAL,
                "cipher sealed exactly " + TOTAL + " payloads (got " + cipher.count() + ")");

        if (!ordered) {
            System.out.println("first 20 written values: " + first(actual, 20));
            System.out.println("first 20 expected values: " + first(expected, 20));
        }

        System.out.println();
        System.out.println("checks: " + checks);
        if (failures == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: FAILED (" + failures + " of " + checks + " checks)");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- fakes

    /** Stamps every sealed payload with the counter value and widens the race window. */
    private static final class SequenceCipher implements Cipher {
        private final AtomicInteger next = new AtomicInteger();

        @Override
        public byte[] seal(byte[] plaintext) {
            int value = next.getAndIncrement();
            Thread.yield();
            return new byte[] { (byte) value };
        }

        @Override
        public byte[] open(byte[] ciphertext) {
            throw new UnsupportedOperationException("open() is not used by this suite");
        }

        int count() {
            return next.get();
        }
    }

    /** Records the frame payloads in the order the delegate transport received them. */
    private static final class RecordingTransport implements Transport {
        private final CopyOnWriteArrayList<Integer> frames = new CopyOnWriteArrayList<Integer>();

        @Override
        public void send(byte[] payload) {
            frames.add(payload[0] & 0xFF);
        }

        @Override
        public byte[] receive() {
            return null;
        }

        @Override
        public void close() {
            // nothing to release
        }

        int[] written() {
            List<Integer> copy = new ArrayList<Integer>(frames);
            int[] out = new int[copy.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = copy.get(i);
            }
            return out;
        }
    }

    // ------------------------------------------------------------- helpers

    private static String first(int[] values, int limit) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(limit, values.length); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }

    private static void check(boolean ok, String description) {
        checks++;
        if (ok) {
            System.out.println("[PASS] " + description);
        } else {
            failures++;
            System.out.println("[FAIL] " + description);
        }
    }
}
