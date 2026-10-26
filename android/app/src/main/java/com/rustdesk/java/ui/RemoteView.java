package com.rustdesk.java.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.ViewConfiguration;

/**
 * Remote desktop surface with the mobile gesture model:
 *
 * <ul>
 *   <li>one finger drag pans the canvas (it must not move the remote mouse);
 *   <li>long press then drag moves the remote mouse with the left button held (text selection);
 *   <li>two finger pinch zooms about the focal point, amplified so small gestures respond;
 *   <li>two finger vertical drag also emits wheel notches so the remote scrolls;
 *   <li>single tap is a left click.
 * </ul>
 */
public class RemoteView extends TextureView {

    /** Pinch amplification: the per-event scale factor is raised to this power. */
    private static final float PINCH_GAIN = 1.6f;
    private static final float MIN_SCALE = 0.2f;
    private static final float MAX_SCALE = 8f;

    /** Vertical two finger travel that produces one wheel notch. */
    private static final float WHEEL_STEP_DP = 40f;

    /**
     * Two finger travel before the gesture commits to panning or to wheeling. Without the dead
     * zone every diagonal drag would pan and wheel at the same time.
     */
    private static final float AXIS_LOCK_DP = 24f;

    /** |dy| has to beat |dx| by this factor for a two finger drag to count as a wheel drag. */
    private static final float AXIS_WHEEL_RATIO = 1.5f;

    private static final String TAG = "RemoteView";

    private final Matrix matrix = new Matrix();
    private final float[] pointBuf = new float[2];

    private final float wheelStepPx;
    private final float axisLockPx;

    private float scale = 1f;
    private float offsetX;
    private float offsetY;

    /**
     * Letterbox geometry: remote pixels times {@link #fitScale} are the largest rectangle of the
     * remote aspect ratio that fits the view, centred at {@link #baseX}/{@link #baseY}.
     * {@link #totalScale} is view pixels per remote pixel and includes the user's {@link #scale};
     * it is the single scalar that relates a view coordinate to a remote one.
     */
    private float fitScale = 1f;
    private float baseX;
    private float baseY;
    private float totalScale = 1f;

    /** Remote desktop size in remote pixels; drives the screen to remote mapping. */
    private int remoteWidth = 1920;
    private int remoteHeight = 1080;

    private InputSink input;
    private SurfaceTexture surfaceTexture;
    private Runnable onSurfaceReady;

    private final GestureDetector tapDetector;
    private final ScaleGestureDetector scaleDetector;

    // one finger pan state
    private float lastTouchX;
    private float lastTouchY;
    private boolean panning;

    // two finger focal tracking
    private boolean focalValid;
    private float focalX;
    private float focalY;
    private float wheelAccum;

    // two finger direction lock: pending travel while the gesture is still undecided
    private boolean axisLocked;
    private boolean axisLockWheel;
    private float axisPendingX;
    private float axisPendingY;

    // long press selection state
    private boolean selecting;
    private boolean buttonHeld;
    private float selectRemoteX;
    private float selectRemoteY;
    private float selectAnchorX;
    private float selectAnchorY;
    private boolean selectDragged;
    private final int touchSlop;

    public RemoteView(Context context) {
        super(context);
        setOpaque(true);
        wheelStepPx = Math.max(1f,
                WHEEL_STEP_DP * context.getResources().getDisplayMetrics().density);
        axisLockPx = Math.max(1f,
                AXIS_LOCK_DP * context.getResources().getDisplayMetrics().density);
        touchSlop = Math.max(1, ViewConfiguration.get(context).getScaledTouchSlop());

        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (input == null) return false;
                mapToRemote(e.getX(), e.getY(), pointBuf);
                input.onMouseMove(pointBuf[0], pointBuf[1]);
                input.onMouseDown(pointBuf[0], pointBuf[1], InputSink.BUTTON_LEFT);
                input.onMouseUp(pointBuf[0], pointBuf[1], InputSink.BUTTON_LEFT);
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (input == null) return;
                selecting = true;
                panning = false;
                selectAnchorX = e.getX();
                selectAnchorY = e.getY();
                selectDragged = false;
                mapToRemote(e.getX(), e.getY(), pointBuf);
                selectRemoteX = pointBuf[0];
                selectRemoteY = pointBuf[1];
                buttonHeld = true;
                input.onMouseMove(selectRemoteX, selectRemoteY);
                input.onMouseDown(selectRemoteX, selectRemoteY, InputSink.BUTTON_LEFT);
                Log.d(TAG, "select: long press at " + selectRemoteX + "," + selectRemoteY);
            }
        });

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float raw = detector.getScaleFactor();
                        if (raw <= 0f) return true;
                        float gain = (float) Math.pow(raw, PINCH_GAIN);
                        applyScale(scale * gain, detector.getFocusX(), detector.getFocusY());
                        return true;
                    }
                });
        scaleDetector.setQuickScaleEnabled(false);
        installSurfaceListener();
    }

    public void setInputSink(InputSink sink) {
        this.input = sink;
    }

    public void setRemoteSize(int width, int height) {
        if (width > 0 && height > 0) {
            remoteWidth = width;
            remoteHeight = height;
            applyTransform();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyTransform();
    }

    public void setOnSurfaceReady(Runnable r) {
        this.onSurfaceReady = r;
        if (isAvailable() && surfaceTexture != null && r != null) r.run();
    }

    /** The texture the decoder renders into; valid once the view is attached. */
    public SurfaceTexture obtainSurfaceTexture() {
        if (surfaceTexture == null) surfaceTexture = getSurfaceTexture();
        return surfaceTexture;
    }

    private void installSurfaceListener() {
        setSurfaceTextureListener(new SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                surfaceTexture = st;
                applyTransform();
                if (onSurfaceReady != null) onSurfaceReady.run();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                applyTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            wheelAccum = 0f;
            resetAxisLock();
        }

        if (selecting) {
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && input != null) {
                // While the left button latches a selection, a second finger taps for a right
                // click instead of extending the selection.
                int idx = event.getActionIndex();
                mapToRemote(event.getX(idx), event.getY(idx), pointBuf);
                input.onMouseMove(pointBuf[0], pointBuf[1]);
                input.onMouseDown(pointBuf[0], pointBuf[1], InputSink.BUTTON_RIGHT);
                input.onMouseUp(pointBuf[0], pointBuf[1], InputSink.BUTTON_RIGHT);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && input != null) {
                float rawX = event.getX();
                float rawY = event.getY();
                if (!selectDragged) {
                    // The press is latched at the long press point, which the finger has usually
                    // already crept away from by a pixel or two, so the first MOVE must not be
                    // forwarded or the selection visibly jumps on entry.
                    float moveX = rawX - selectAnchorX;
                    float moveY = rawY - selectAnchorY;
                    if (moveX * moveX + moveY * moveY <= (float) touchSlop * touchSlop) {
                        return true;
                    }
                    selectDragged = true;
                    Log.d(TAG, "select: drag started");
                }
                mapToRemote(rawX, rawY, pointBuf);
                if (pointBuf[0] != selectRemoteX || pointBuf[1] != selectRemoteY) {
                    selectRemoteX = pointBuf[0];
                    selectRemoteY = pointBuf[1];
                    input.onMouseMove(selectRemoteX, selectRemoteY);
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (buttonHeld && input != null) {
                    input.onMouseUp(selectRemoteX, selectRemoteY, InputSink.BUTTON_LEFT);
                }
                selecting = false;
                buttonHeld = false;
            }
            return true;
        }

        if (event.getPointerCount() >= 2) {
            panning = false;
            float[] focal = twoFingerFocal(event);
            if (!focalValid) {
                resetAxisLock();
            } else if (action == MotionEvent.ACTION_MOVE) {
                dragByFingers(focal[0] - focalX, focal[1] - focalY, focal[0], focal[1]);
            }
            focalX = focal[0];
            focalY = focal[1];
            focalValid = true;
            return true;
        }

        focalValid = false;
        tapDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                panning = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (panning) {
                    offsetX += event.getX() - lastTouchX;
                    offsetY += event.getY() - lastTouchY;
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    applyTransform();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                panning = false;
                return true;
            default:
                return true;
        }
    }

    private float[] twoFingerFocal(MotionEvent event) {
        float x = (event.getX(0) + event.getX(1)) / 2f;
        float y = (event.getY(0) + event.getY(1)) / 2f;
        return new float[] {x, y};
    }

    private void resetAxisLock() {
        axisLocked = false;
        axisLockWheel = false;
        axisPendingX = 0f;
        axisPendingY = 0f;
    }

    /**
     * Two finger drag. The first {@link #axisLockPx} of travel only fill a buffer, then the
     * gesture commits for the rest of its life to either panning the canvas or wheeling the
     * remote, never both. The buffered travel is replayed into the chosen channel so the motion
     * stays continuous.
     */
    private void dragByFingers(float dx, float dy, float viewX, float viewY) {
        if (!axisLocked) {
            axisPendingX += dx;
            axisPendingY += dy;
            if (axisPendingX * axisPendingX + axisPendingY * axisPendingY
                    < axisLockPx * axisLockPx) {
                return;
            }
            axisLocked = true;
            axisLockWheel = Math.abs(axisPendingY) > Math.abs(axisPendingX) * AXIS_WHEEL_RATIO;
            dx = axisPendingX;
            dy = axisPendingY;
            Log.d(TAG, "two finger lock: " + (axisLockWheel ? "wheel" : "pan"));
        }
        if (axisLockWheel) {
            scrollByFingers(dy, viewX, viewY);
        } else {
            offsetX += dx;
            offsetY += dy;
            applyTransform();
        }
    }

    /**
     * Turns two finger vertical travel into wheel notches. Fingers moving down (dy &gt; 0) drag
     * the content down, i.e. scroll up, which the host expects as a positive wheel delta.
     */
    private void scrollByFingers(float dy, float viewX, float viewY) {
        if (input == null) return;
        wheelAccum += dy;
        while (wheelAccum >= wheelStepPx) {
            wheelAccum -= wheelStepPx;
            dispatchWheel(viewX, viewY, 1);
        }
        while (wheelAccum <= -wheelStepPx) {
            wheelAccum += wheelStepPx;
            dispatchWheel(viewX, viewY, -1);
        }
    }

    private void dispatchWheel(float viewX, float viewY, int delta) {
        mapToRemote(viewX, viewY, pointBuf);
        input.onWheel(pointBuf[0], pointBuf[1], delta);
    }

    private void applyScale(float newScale, float focusX, float focusY) {
        float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (Math.abs(clamped - scale) < 0.0001f) return;
        updateFit();
        float ratio = clamped / scale;
        // Keep the content under the focal point fixed; the translation is base + offset, so the
        // fit centring has to take part in the pivot.
        offsetX = focusX - baseX - (focusX - baseX - offsetX) * ratio;
        offsetY = focusY - baseY - (focusY - baseY - offsetY) * ratio;
        scale = clamped;
        applyTransform();
    }

    /**
     * Derives the letterbox rectangle. Without it the texture is stretched to the view aspect
     * ratio, so remote pixels are not square and a tap lands away from what is under the finger.
     */
    private void updateFit() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            fitScale = 1f;
            baseX = 0f;
            baseY = 0f;
            totalScale = scale;
            return;
        }
        fitScale = Math.min(w / (float) remoteWidth, h / (float) remoteHeight);
        baseX = (w - remoteWidth * fitScale) / 2f;
        baseY = (h - remoteHeight * fitScale) / 2f;
        totalScale = fitScale * scale;
    }

    /**
     * {@code setScale} multiplies the view sized quad that the texture is stretched over, not the
     * remote pixels, so {@link #totalScale} is converted back per axis: on a view of the remote
     * aspect ratio both factors equal {@code totalScale} and this is a plain uniform scale.
     */
    private void applyTransform() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        updateFit();
        matrix.setScale(totalScale * remoteWidth / w, totalScale * remoteHeight / h);
        matrix.postTranslate(baseX + offsetX, baseY + offsetY);
        setTransform(matrix);
    }

    /** Maps a view coordinate to remote desktop pixels. */
    public void mapToRemote(float viewX, float viewY, float[] out) {
        updateFit();
        // totalScale is view pixels per remote pixel, so the quotient is already a remote pixel.
        float remoteX = (viewX - baseX - offsetX) / totalScale;
        float remoteY = (viewY - baseY - offsetY) / totalScale;
        out[0] = clamp(remoteX, 0f, remoteWidth - 1f);
        out[1] = clamp(remoteY, 0f, remoteHeight - 1f);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Convenience for the persistent left/right buttons: click at the current centre. */
    public void clickAtCenter(int button) {
        if (input == null) return;
        float[] out = new float[2];
        mapToRemote(getWidth() / 2f, getHeight() / 2f, out);
        input.onMouseDown(out[0], out[1], button);
        input.onMouseUp(out[0], out[1], button);
    }

    public void pressAtCenter(int button) {
        if (input == null) return;
        float[] out = new float[2];
        mapToRemote(getWidth() / 2f, getHeight() / 2f, out);
        input.onMouseDown(out[0], out[1], button);
    }

    public void releaseAtCenter(int button) {
        if (input == null) return;
        float[] out = new float[2];
        mapToRemote(getWidth() / 2f, getHeight() / 2f, out);
        input.onMouseUp(out[0], out[1], button);
    }

    public float getScale() {
        return scale;
    }

    public void resetTransform() {
        scale = 1f;
        offsetX = 0f;
        offsetY = 0f;
        applyTransform();
    }
}
