package com.rustdesk.java.host;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Turns captured screen frames into an H.264 Annex-B byte stream with a MediaCodec encoder.
 *
 * <p>The encoder takes its input through a {@link Surface} configured as
 * {@link MediaCodecInfo.CodecCapabilities#COLOR_FormatSurface}: the capture side draws each frame
 * onto {@link #getInputSurface()}, the encoder timestamps and converts it on its own, so no frame
 * data passes through this class. {@link #drain} hands out whatever the codec has produced.</p>
 *
 * <p>The output is forwarded unchanged, SPS/PPS included. An AVC encoder configured this way emits
 * Annex-B, and the parameter sets travel either inside the key frame buffer or in a separate
 * buffer flagged {@link MediaCodec#BUFFER_FLAG_CODEC_CONFIG} right before it. The transport this
 * feeds puts one buffer into one self-contained frame, so extracting the parameter sets and
 * re-attaching them to the key frame would only duplicate what the decoder already receives.</p>
 *
 * <p>{@link #start} and {@link #stop} are exclusive. {@link #drain} and {@link #requestKeyFrame}
 * may run on different threads; the read/write lock keeps both of them away from a teardown that
 * releases the codec.</p>
 */
public final class HostCaptureCodec {

    public static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    /** Seconds between IDR frames, the worst case for the viewer to recover from a lost frame. */
    private static final int I_FRAME_INTERVAL_SECONDS = 2;

    /** One encoded H.264 access unit, in the shape the wire message needs. */
    public static final class EncodedFrame {
        /** H.264 Annex-B access unit; never null on a frame returned by {@link #drain}. */
        public byte[] data;
        /** True when the codec flagged the buffer as an IDR/key frame. */
        public boolean key;
        /** Presentation timestamp in milliseconds, taken from the codec buffer timestamp. */
        public long ptsMs;

        public EncodedFrame() {
        }

        public EncodedFrame(byte[] data, boolean key, long ptsMs) {
            this.data = data;
            this.key = key;
            this.ptsMs = ptsMs;
        }
    }

    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    /** Null until {@link #start} succeeds and again after {@link #stop}; guarded by the lock. */
    private MediaCodec codec;
    private Surface inputSurface;

    /**
     * The format a {@code COLOR_FormatSurface} AVC encoder has to be configured with, before
     * {@link MediaCodec#createInputSurface} can be called.
     */
    public static MediaFormat createFormat(int width, int height, int fps, int bitRate) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("size must be positive: " + width + "x" + height);
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be positive: " + fps);
        }
        if (bitRate <= 0) {
            throw new IllegalArgumentException("bit rate must be positive: " + bitRate);
        }
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        // VBR spends the bit budget on the frames that need it; start() falls back to CBR when the
        // codec reports that it only does constant bit rate.
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        return format;
    }

    /**
     * Creates, configures and starts the encoder.
     *
     * @throws IllegalStateException when the codec cannot be created or started, or when this
     *                               instance is already started
     */
    public void start(int width, int height, int fps, int bitRate) {
        MediaFormat format = createFormat(width, height, fps, bitRate);
        lifecycle.writeLock().lock();
        try {
            if (codec != null) {
                throw new IllegalStateException("encoder already started");
            }
            MediaCodec created = null;
            Surface surface = null;
            try {
                created = MediaCodec.createEncoderByType(MIME_TYPE);
                if (!supportsBitrateMode(created,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                    format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                }
                created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                surface = created.createInputSurface();
                created.start();
            } catch (IOException | RuntimeException e) {
                // A half-configured codec still holds vendor resources, but the caller has to see
                // the original failure, so the cleanup below must not replace it.
                releaseQuietly(surface, created);
                throw new IllegalStateException("cannot start the H.264 encoder: " + e, e);
            }
            codec = created;
            inputSurface = surface;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /** The encoder input, or null before {@link #start} and after {@link #stop}. */
    public Surface getInputSurface() {
        lifecycle.readLock().lock();
        try {
            return inputSurface;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Returns the next encoded frame, or null when the codec has nothing ready.
     *
     * <p>The call waits at most {@code timeoutUs} microseconds inside
     * {@link MediaCodec#dequeueOutputBuffer}. When that elapses the codec answers
     * {@link MediaCodec#INFO_TRY_AGAIN_LATER} and this method returns null instead of asking
     * again, so the caller polling in a loop owns the pacing and can stop between calls; nothing
     * spins in here. A negative timeout is treated as 0, which makes the call non-blocking.</p>
     *
     * @throws IllegalStateException when the codec fails while dequeuing or copying a buffer
     */
    public EncodedFrame drain(long timeoutUs) {
        long timeout = timeoutUs < 0 ? 0 : timeoutUs;
        lifecycle.readLock().lock();
        try {
            MediaCodec active = codec;
            if (active == null) {
                return null;
            }
            // Per call state: two threads may drain at the same time.
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int index;
            try {
                index = active.dequeueOutputBuffer(info, timeout);
            } catch (RuntimeException e) {
                throw new IllegalStateException("H.264 dequeueOutputBuffer failed: " + e, e);
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The parameter sets this event advertises reach the caller through the output
                // buffer that follows, so there is nothing to apply here. -2 is still named so it
                // is never mistaken for "no output available".
                return null;
            }
            if (index < 0) {
                // INFO_TRY_AGAIN_LATER (-1): no output within timeoutUs, see above.
                return null;
            }
            EncodedFrame frame = null;
            RuntimeException failure = null;
            try {
                ByteBuffer buffer = active.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    buffer.get(data);
                    // BUFFER_FLAG_KEY_FRAME is the IDR marker. BUFFER_FLAG_CODEC_CONFIG is not
                    // part of the test: a parameter set buffer is not a sync point.
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    frame = new EncodedFrame(data, key, info.presentationTimeUs / 1000L);
                }
            } catch (RuntimeException e) {
                failure = new IllegalStateException("H.264 output buffer copy failed: " + e, e);
            } finally {
                // The codec keeps the buffer until it is released back to the pool, and the pool
                // is small, so skipping this on any path (empty buffer, copy failure) stalls the
                // encoder after a few frames. Rendering stays off: there is no output surface.
                try {
                    active.releaseOutputBuffer(index, false);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = new IllegalStateException(
                                "H.264 releaseOutputBuffer failed: " + e, e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            return frame;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Not available on a surface-fed encoder: it has no byte[] input path, and going through a
     * Canvas would drop the frame timestamp that becomes {@link EncodedFrame#ptsMs}. Draw the
     * frame onto {@link #getInputSurface()} instead, which also lets the encoder timestamp it.
     */
    public void queueFrame(byte[] rgba, int width, int height, long ptsMs) {
        throw new UnsupportedOperationException(
                "surface input only: draw the frame onto getInputSurface()");
    }

    /**
     * Asks the encoder for an IDR frame in its next output. A no-op before {@link #start} and
     * after {@link #stop}, where no encoder is left to ask.
     *
     * @throws IllegalStateException when the codec rejects the request
     */
    public void requestKeyFrame() {
        // The read lock is what keeps stop() from releasing the codec between the check and the
        // call. Sharing it with drain() is deliberate: a key frame request then never waits
        // longer than the drain timeout of a concurrent drain.
        lifecycle.readLock().lock();
        try {
            MediaCodec active = codec;
            if (active == null) {
                return;
            }
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            try {
                active.setParameters(parameters);
            } catch (RuntimeException e) {
                throw new IllegalStateException("H.264 key frame request failed: " + e, e);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    /**
     * Stops and releases the codec and its input surface. Idempotent: the fields are cleared
     * before anything is released, so a repeated call finds nothing to tear down and returns.
     *
     * @throws IllegalStateException when the codec fails to stop or release; everything has been
     *                               released by then, so a repeated call is still a no-op
     */
    public void stop() {
        MediaCodec stopping;
        Surface surface;
        lifecycle.writeLock().lock();
        try {
            stopping = codec;
            surface = inputSurface;
            codec = null;
            inputSurface = null;
        } finally {
            lifecycle.writeLock().unlock();
        }
        RuntimeException failure = null;
        if (surface != null) {
            try {
                surface.release();
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (stopping != null) {
            // No end of stream signal: nothing would read the frames it produces, and draining
            // here could block the thread that is tearing the encoder down.
            try {
                stopping.stop();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
            try {
                stopping.release();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw new IllegalStateException("H.264 encoder teardown failed: " + failure, failure);
        }
    }

    private static boolean supportsBitrateMode(MediaCodec encoder, int mode) {
        MediaCodecInfo info = encoder.getCodecInfo();
        MediaCodecInfo.EncoderCapabilities capabilities =
                info.getCapabilitiesForType(MIME_TYPE).getEncoderCapabilities();
        return capabilities != null && capabilities.isBitrateModeSupported(mode);
    }

    private static void releaseQuietly(Surface surface, MediaCodec encoder) {
        if (surface != null) {
            try {
                surface.release();
            } catch (RuntimeException ignored) {
                // The codec is dropped anyway; start() reports the failure that is pending.
            }
        }
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
                // The codec may not even be configured, which makes stop() throw.
            }
            try {
                encoder.release();
            } catch (RuntimeException ignored) {
                // Same as above.
            }
        }
    }
}
