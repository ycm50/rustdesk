package com.rustdesk.java.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The persistent left-mouse / right-mouse buttons in the left tenth of the screen.
 *
 * <p>Only the two circles consume touches. Every other pixel in the strip returns false so the
 * event falls through to the remote surface behind it: the strip itself must never behave as a
 * second mouse area.
 */
public class ClickButtonsOverlay extends View {

    private static final String TAG = "RDJAVA";

    /** Fraction of the view height each button band occupies (top half, bottom half). */

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF leftZone = new RectF();
    private final RectF rightZone = new RectF();
    private final RectF outlineRect = new RectF();

    private final RemoteView target;

    /** Pointer id that owns the current left-button gesture, or -1 when no gesture is running. */
    private int activePointer = -1;

    /** True while the remote left button is down and has not been released yet. */
    private boolean pressed;

    /** True once the hold timer of the current gesture fired. */
    private boolean holdFired;

    /** True once the current gesture was abandoned, e.g. the finger left the circle. */
    private boolean canceled;

    /** Extra pixels around a circle that still count as inside, to tolerate finger jitter. */
    private final float exitSlop;

    /** Long press delay before the left button latches down. */
    private static final long HOLD_DELAY_MS = 260L;

    private final Runnable startHold = new Runnable() {
        @Override
        public void run() {
            if (activePointer == -1 || holdFired || canceled) return;
            holdFired = true;
            pressed = true;
            target.pressAtCenter(InputSink.BUTTON_LEFT);
            haptic(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
            Log.i(TAG, "left button latched by long press");
        }
    };

    public ClickButtonsOverlay(Context context, RemoteView target) {
        super(context);
        this.target = target;
        exitSlop = ViewConfiguration.get(context).getScaledTouchSlop() * 0.5f;
        fill.setColor(0x59000000);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xB3FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutCircles(w, h);
    }

    /**
     * Recomputes the circle rects from the current size. Layout events can fire with an
     * intermediate height, so the draw and touch paths call this instead of trusting the cached
     * rects.
     */
    private void relayout() {
        layoutCircles(getWidth(), getHeight());
    }

    /**
     * Splits the strip into a top and a bottom zone. The zones cover the whole strip, so the
     * user does not have to hit a small circle, and the strip never passes a touch to the video.
     */
    private void layoutCircles(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float inset = Math.min(w, h * 0.02f) * 0.12f;
        float mid = h * 0.5f;
        leftZone.set(inset, inset, w - inset, mid - inset);
        rightZone.set(inset, mid + inset, w - inset, h - inset);
        label.setTextSize(Math.min(w * 0.5f, mid * 0.22f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        relayout();
        drawButton(canvas, leftZone, "L", isLeftHighlighted());
        drawButton(canvas, rightZone, "R", false);
    }

    private boolean isLeftHighlighted() {
        return pressed || (activePointer != -1 && !canceled);
    }

    private void drawButton(Canvas canvas, RectF circle, String text, boolean highlighted) {
        float ringWidth = Math.max(3f, circle.width() * 0.06f);
        float corner = Math.min(circle.width(), circle.height()) * 0.18f;
        fill.setColor(highlighted ? 0xCC33B5E5 : 0x59000000);
        canvas.drawRoundRect(circle, corner, corner, fill);

        // Ring drawn just inside the zone so the outline never suggests a larger target.
        outlineRect.set(circle);
        outlineRect.inset(ringWidth * 0.5f, ringWidth * 0.5f);
        stroke.setColor(highlighted ? 0xFFFFFFFF : 0xB3FFFFFF);
        stroke.setStrokeWidth(ringWidth);
        canvas.drawRoundRect(outlineRect, corner, corner, stroke);

        float cy = circle.centerY() - (label.descent() + label.ascent()) / 2f;
        canvas.drawText(text, circle.centerX(), cy, label);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        relayout();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX();
                float y = event.getY();
                if (!inTopHalf(x, y)) {
                    // Bottom half: a plain right click, nothing to latch.
                    target.clickAtCenter(InputSink.BUTTON_RIGHT);
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                }
                // Top half: the left button, which latches if held.
                activePointer = event.getPointerId(0);
                holdFired = false;
                canceled = false;
                postDelayed(startHold, HOLD_DELAY_MS);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activePointer == -1 || canceled) return activePointer != -1;
                int index = event.findPointerIndex(activePointer);
                if (index < 0) return true;
                if (!insideStrip(event.getX(index), event.getY(index))) {
                    // The finger left the button: release a latched button and abandon the tap.
                    cancelGesture("move-out");
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // A second finger on the right circle while the left button latches: right click.
                int index = event.getActionIndex();
                if (!inTopHalf(event.getX(index), event.getY(index))) {
                    target.clickAtCenter(InputSink.BUTTON_RIGHT);
                    haptic(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                if (activePointer != -1 && event.getPointerId(index) == activePointer) {
                    finishGesture();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (activePointer != -1 && event.getPointerId(event.getActionIndex()) == activePointer) {
                    finishGesture();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelGesture("cancel");
                resetGesture();
                return true;
            }
            default:
                return activePointer != -1;
        }
    }

    /** Ends the gesture owned by the active pointer: release a latch, or click for a short tap. */
    private void finishGesture() {
        removeCallbacks(startHold);
        if (pressed) {
            releaseLeft("up");
        } else if (!holdFired && !canceled) {
            target.clickAtCenter(InputSink.BUTTON_LEFT);
            haptic(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        resetGesture();
    }

    /** Abandons the current gesture. A latched left button is released here. */
    private void cancelGesture(String reason) {
        if (canceled) return;
        removeCallbacks(startHold);
        if (pressed) {
            releaseLeft(reason);
        }
        canceled = true;
        invalidate();
        if (activePointer != -1) {
            Log.i(TAG, "left gesture canceled: " + reason);
        }
    }

    /**
     * Releases the remote left button. Guarded by {@link #pressed} so overlapping paths (move-out
     * followed by up, cancel after detach, ...) can never send two releases for one press.
     */
    private void releaseLeft(String reason) {
        if (!pressed) return;
        pressed = false;
        target.releaseAtCenter(InputSink.BUTTON_LEFT);
        invalidate();
        Log.i(TAG, "left button released: " + reason);
    }

    private void resetGesture() {
        removeCallbacks(startHold);
        activePointer = -1;
        holdFired = false;
        canceled = false;
        invalidate();
    }

/**
     * The whole strip is the hit area and it is split in half: the top half is the left button,
     * the bottom half the right one. Tapping the strip therefore never falls through to the video,
     * and no precision aim at a circle is needed.
     */
    private boolean inTopHalf(float x, float y) {
        return y < getHeight() * 0.5f;
    }

    private boolean insideStrip(float x, float y) {
        return x >= 0 && x <= getWidth() && y >= 0 && y <= getHeight();
    }


    private void haptic(int feedbackConstant) {
        try {
            performHapticFeedback(feedbackConstant);
        } catch (RuntimeException ignored) {
            // Haptics are best effort; an unattached view or a device without a vibrator is fine.
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(startHold);
        releaseLeft("detach");
        resetGesture();
        super.onDetachedFromWindow();
    }
}




