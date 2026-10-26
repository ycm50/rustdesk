package com.rustdesk.java.codec;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders RustDesk video frames through MediaCodec onto a {@link Surface}.
 *
 * <p>RustDesk sends one encoded frame per message and may switch codec, resolution or size at any
 * time (for example when the peer changes display). The decoder therefore re-creates itself when
 * the MIME type changes and asks for adaptive playback so a resolution change does not need a full
 * teardown.
 */
public class VideoDecoder {

    public static final String MIME_H264 = MediaFormat.MIMETYPE_VIDEO_AVC;
    public static final String MIME_H265 = MediaFormat.MIMETYPE_VIDEO_HEVC;
    public static final String MIME_VP8 = "video/x-vnd.on2.vp8";
    public static final String MIME_VP9 = "video/x-vnd.on2.vp9";
    public static final String MIME_AV1 = MediaFormat.MIMETYPE_VIDEO_AV1;

    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final Surface surface;
    private final BlockingQueue<Sample> pending = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object lock = new Object();

    private MediaCodec codec;
    private String mime;
    private int width;
    private int height;
    private Thread decodeThread;

    private volatile boolean configured;
    private volatile String lastError;
    private volatile int decodedFrameCount;

    public VideoDecoder(Surface surface) {
        this.surface = surface;
    }

    public static String mimeFor(int codecId) {
        switch (codecId) {
            case 1:
                return MIME_H264;
            case 2:
                return MIME_H265;
            case 3:
                return MIME_VP8;
            case 4:
                return MIME_VP9;
            case 5:
                return MIME_AV1;
            default:
                return null;
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        decodeThread = new Thread(this::decodeLoop, "video-decode");
        decodeThread.start();
    }

    /** Queues one encoded frame; {@code key} marks an IDR/keyframe. */
    public void queueFrame(byte[] data, boolean key, long ptsUs) {
        if (!running.get() || data == null || data.length == 0) return;
        pending.offer(new Sample(data, key, ptsUs));
    }

    public void setMime(String newMime, int width, int height) {
        synchronized (lock) {
            boolean changed = !equalsSafe(this.mime, newMime)
                    || (width > 0 && height > 0 && (width != this.width || height != this.height));
            if (!changed) return;
            this.mime = newMime;
            if (width > 0) this.width = width;
            if (height > 0) this.height = height;
            releaseCodecLocked();
            // A new MIME or size invalidates queued samples for the old configuration.
            pending.clear();
        }
    }

    private boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get()) {
            Sample sample;
            try {
                sample = pending.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                recordFailure("poll", e);
                Thread.currentThread().interrupt();
                return;
            }
            ensureCodec();
            MediaCodec c = codec;
            if (c == null) {
                if (sample != null) {
                    // Not configured yet: drop and wait for configuration.
                    continue;
                }
                continue;
            }
            if (sample != null) {
                feed(c, sample);
            }
            drain(c, info);
        }
        synchronized (lock) {
            releaseCodecLocked();
        }
    }

    private void ensureCodec() {
        synchronized (lock) {
            if (codec != null || mime == null) return;
            try {
                MediaFormat format = MediaFormat.createVideoFormat(mime, width > 0 ? width : 1920, height > 0 ? height : 1080);
                // Adaptive playback lets the vendor decoder follow size changes in-band.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    format.setInteger(MediaFormat.KEY_MAX_WIDTH, Math.max(width * 2, 1920));
                    format.setInteger(MediaFormat.KEY_MAX_HEIGHT, Math.max(height * 2, 1080));
                }
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, surface, null, 0);
                codec.start();
                configured = true;
                android.util.Log.i("RDJAVA", "VideoDecoder configured: " + mime + " "
                        + format.getInteger(MediaFormat.KEY_WIDTH) + "x"
                        + format.getInteger(MediaFormat.KEY_HEIGHT));
            } catch (IOException | RuntimeException e) {
                releaseCodecLocked();
                recordFailure("configure", e);
            }
        }
    }

    private void feed(MediaCodec c, Sample sample) {
        try {
            int index = c.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
            if (index < 0) {
                // No input room this round; keep the sample for the next iteration.
                pending.offer(sample);
                return;
            }
            java.nio.ByteBuffer buffer = c.getInputBuffer(index);
            if (buffer == null) {
                pending.offer(sample);
                return;
            }
            buffer.clear();
            if (buffer.remaining() < sample.data.length) {
                // Frame does not fit a single buffer; drop rather than corrupt the stream.
                c.queueInputBuffer(index, 0, 0, sample.ptsUs, 0);
                return;
            }
            buffer.put(sample.data);
            int flags = sample.key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            c.queueInputBuffer(index, 0, sample.data.length, sample.ptsUs, flags);
            decodedFrameCount++;
        } catch (IllegalStateException e) {
            recordFailure("queueInputBuffer", e);
            // Codec was torn down concurrently; the loop will reconfigure.
        }
    }

    private void drain(MediaCodec c, MediaCodec.BufferInfo info) {
        try {
            while (true) {
                int outIndex = c.dequeueOutputBuffer(info, 0);
                if (outIndex >= 0) {
                    // Rendering to the Surface is what displays the frame.
                    c.releaseOutputBuffer(outIndex, true);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = c.getOutputFormat();
                    int w = out.getInteger(MediaFormat.KEY_WIDTH);
                    int h = out.getInteger(MediaFormat.KEY_HEIGHT);
                    if (w > 0 && h > 0) {
                        synchronized (lock) {
                            width = w;
                            height = h;
                        }
                    }
                } else {
                    return;
                }
            }
        } catch (IllegalStateException e) {
            recordFailure("drain", e);
            // torn down
        }
    }

    private void releaseCodecLocked() {
        MediaCodec c = codec;
        codec = null;
        configured = false;
        if (c != null) {
            try {
                c.stop();
            } catch (Exception e) {
                recordFailure("codec.stop", e);
            }
            try {
                c.release();
            } catch (Exception e) {
                recordFailure("codec.release", e);
            }
        }
    }

    /** Records a failure for diagnostics; the most recent failure wins. */
    private void recordFailure(String where, Throwable e) {
        lastError = where + ": " + e;
        android.util.Log.e("RDJAVA", "VideoDecoder " + where + " failed", e);
    }

    public void release() {
        running.set(false);
        Thread t = decodeThread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                recordFailure("join", e);
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            releaseCodecLocked();
        }
    }

    public int getWidth() {
        synchronized (lock) {
            return width;
        }
    }

    public int getHeight() {
        synchronized (lock) {
            return height;
        }
    }

    /** True once configure() and start() succeeded and the codec is still live. */
    public boolean isConfigured() {
        return configured;
    }

    /** Message of the most recent failure, or null when nothing has failed yet. */
    public String getLastError() {
        return lastError;
    }

    /** Number of frames handed to the decoder through queueInputBuffer(). */
    public int getDecodedFrameCount() {
        return decodedFrameCount;
    }

    private static final class Sample {
        final byte[] data;
        final boolean key;
        final long ptsUs;

        Sample(byte[] data, boolean key, long ptsUs) {
            this.data = data;
            this.key = key;
            this.ptsUs = ptsUs;
        }
    }
}

