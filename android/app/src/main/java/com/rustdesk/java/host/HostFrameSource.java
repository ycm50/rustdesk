package com.rustdesk.java.host;

import android.util.Log;

/**
 * Joins the capture side, the EGL uploader and the H.264 encoder into the
 * {@link HostSession.FrameSource} a {@link HostSession} streams from.
 *
 * <p>A captured frame travels one way: {@link #onCapturedFrame} hands the RGBA buffer to
 * {@link GlFrameUploader}, which draws it onto the encoder input surface returned by
 * {@link HostCaptureCodec#getInputSurface()}; the encoder timestamps and encodes it, and
 * {@link #nextFrame} hands the resulting access unit to the session. No pixel or bitstream
 * data is copied here, so those two objects do all the work and this class only owns their
 * lifetime.</p>
 *
 * <p>Capture and streaming run on different threads and are guarded by two separate locks:
 * {@code uploadLock} keeps {@link #onCapturedFrame} out while {@link #stop} releases the
 * uploader, and {@code drainLock} does the same for {@link #nextFrame} against the encoder
 * teardown. Neither thread ever holds both, so a concurrent drain cannot stall capture.</p>
 *
 * <p>Frames delivered before {@link #start} or after {@link #stop} are dropped: the capture
 * service may deliver one last frame while it is shutting down.</p>
 */
public final class HostFrameSource implements HostSession.FrameSource {

    private static final String LOG_TAG = "RDJAVA";

    private final int width;
    private final int height;
    private final int fps;
    private final int bitRate;

    private final HostCaptureCodec codec = new HostCaptureCodec();
    private final GlFrameUploader uploader = new GlFrameUploader();

    /**
     * Guards the uploader, so a capture frame cannot be drawn while {@link #stop} releases it.
     * Never held together with {@link #drainLock}.
     */
    private final Object uploadLock = new Object();

    /**
     * Guards the encoder, so {@link #nextFrame} cannot drain while {@link #stop} tears it down.
     */
    private final Object drainLock = new Object();

    /** True between a successful {@link #start} and the next {@link #stop}. */
    private volatile boolean started;

    /**
     * Set by the first failed draw of a burst and cleared by the next successful one, so a
     * surface that stays broken cannot log once per captured frame. Guarded by uploadLock.
     */
    private boolean uploadFailureLogged;

    /**
     * @param width  encoder width in pixels; validated by {@link #start}
     * @param height encoder height in pixels; validated by {@link #start}
     * @param fps    requested frame rate, used as the encoder's frame-rate hint
     * @param bitRate requested target bit rate in bits per second
     */
    public HostFrameSource(int width, int height, int fps, int bitRate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitRate = bitRate;
    }

    /**
     * Starts the encoder and binds the uploader to its input surface. A no-op when this source
     * is already started, so a repeated start cannot leak a second codec.
     *
     * @throws IllegalArgumentException when a size, the frame rate or the bit rate is not
     *                                  positive
     * @throws IllegalStateException    when the encoder or the EGL context cannot be created;
     *                                  the encoder is released again before the exception leaves
     */
    public void start() {
        synchronized (uploadLock) {
            if (started) {
                return;
            }
            try {
                codec.start(width, height, fps, bitRate);
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "frame source: cannot start the encoder: " + e, e);
                throw e;
            }
            try {
                uploader.init(codec.getInputSurface(), width, height);
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "frame source: cannot start the EGL uploader: " + e, e);
                // The encoder is useless without the uploader, so do not leave it running.
                try {
                    codec.stop();
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }
            uploadFailureLogged = false;
            started = true;
            Log.i(LOG_TAG, "frame source started, " + width + "x" + height
                    + " fps=" + fps + " bitRate=" + bitRate);
        }
    }

    /**
     * Feeds one captured frame to the encoder. Called on the capture thread; frames that arrive
     * while this source is not started are dropped.
     *
     * <p>A failed draw is logged once per burst and does not throw: the exception would
     * otherwise propagate into the platform's capture callback and kill the capture thread. The
     * next successful draw re-arms the log.</p>
     *
     * @param rgba tightly packed top-row-first RGBA_8888 pixels
     * @param ptsMs capture presentation timestamp in milliseconds, as the capture side reports it
     */
    public void onCapturedFrame(byte[] rgba, int width, int height, long ptsMs) {
        synchronized (uploadLock) {
            if (!started) {
                return;
            }
            try {
                // Milliseconds to nanoseconds happens here and nowhere else: this is the only
                // point where the capture clock meets EGL's, and the encoder turns the result
                // back into milliseconds for the wire frame.
                uploader.draw(rgba, width, height, ptsMs * 1_000_000L);
                uploadFailureLogged = false;
            } catch (RuntimeException e) {
                if (!uploadFailureLogged) {
                    uploadFailureLogged = true;
                    Log.e(LOG_TAG, "frame source: cannot upload a captured frame: " + e, e);
                }
            }
        }
    }

    /**
     * Hands the session the next encoded access unit. Called on the session thread and never
     * waits for the encoder: {@link HostCaptureCodec#drain} is asked with a zero timeout and
     * returns null when nothing is ready.
     *
     * <p>A drain failure is logged and rethrown, which {@link HostSession} turns into the end of
     * the session with the failure in the reason.</p>
     */
    @Override
    public boolean nextFrame(HostSession.Frame out) {
        synchronized (drainLock) {
            if (!started) {
                return false;
            }
            HostCaptureCodec.EncodedFrame frame;
            try {
                frame = codec.drain(0);
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "frame source: cannot drain the encoder: " + e, e);
                throw e;
            }
            if (frame == null) {
                return false;
            }
            out.data = frame.data;
            out.key = frame.key;
            out.ptsMs = frame.ptsMs;
            return true;
        }
    }

    /**
     * Releases the uploader and stops the encoder. Idempotent, and nothing throws: a teardown
     * failure is logged so the rest of the shutdown still runs.
     */
    public void stop() {
        synchronized (uploadLock) {
            if (!started) {
                return;
            }
            // Cleared first so a capture thread that is still delivering frames drops them
            // instead of drawing into a surface that is about to disappear.
            started = false;
            uploadFailureLogged = false;
            try {
                uploader.release();
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "frame source: cannot release the EGL uploader: " + e, e);
            }
        }
        synchronized (drainLock) {
            try {
                codec.stop();
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "frame source: cannot stop the encoder: " + e, e);
            }
        }
        Log.i(LOG_TAG, "frame source stopped");
    }
}
