package com.rustdesk.java.host;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.rustdesk.java.MainActivity;

import java.nio.ByteBuffer;

/**
 * Host side screen capture: MediaProjection drives an {@link ImageReader} and every frame is
 * handed out as raw RGBA, without any encoding.
 *
 * <p>{@link MainActivity} asks {@link MediaProjectionManager} for consent and starts this service
 * with {@code startForegroundService()}, passing the consent result in
 * {@link MainActivity#EXTRA_RESULT_CODE} and {@link MainActivity#EXTRA_RESULT_DATA}. Everything
 * else is this service's job, in this order:
 * <ol>
 * <li>{@link #onStartCommand} posts the media projection notification before it reads the intent.
 * A startForegroundService() launch that never posts one is killed by the platform with a
 * RemoteServiceException after about five seconds;</li>
 * <li>only then does it hand the consent to {@link #setProjectionResult} and call
 * {@link #startCapture}, because Android 10+ rejects {@code getMediaProjection()} unless the
 * caller is already a foreground service of type {@code mediaProjection};</li>
 * <li>{@link #onDestroy} releases the projection and removes the notification.</li>
 * </ol>
 */
public class ScreenCaptureService extends Service {

    /** Receives one tightly packed RGBA_8888 frame per callback, top row first. */
    public interface FrameListener {
        void onFrame(byte[] rgba, int width, int height, long ptsMs);
    }

    private static final String TAG = "ScreenCaptureService";
    private static final String DISPLAY_NAME = "rustdesk-host";

    /** Two buffers are enough to always have a spare while the previous frame is being copied. */
    private static final int MAX_IMAGES = 2;

    private static final int DEFAULT_FPS = 30;

    /**
     * MainActivity logs under this tag, so one logcat filter shows the whole host start-up path:
     * consent result, foreground transition, capture start.
     */
    private static final String LOG_TAG = "RDJAVA";

    private static final String CHANNEL_ID = "screen-capture";
    private static final int NOTIFICATION_ID = 2001;

    /** Only used when neither WindowMetrics nor DisplayMetrics reports a positive size. */
    private static final int FALLBACK_WIDTH = 1080;
    private static final int FALLBACK_HEIGHT = 1920;

    private final Object lock = new Object();

    /** True between a successful startForeground() and the stopForeground() that removes it. */
    private boolean foregroundStarted;

    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private int resultCode;
    private Intent resultData;

    private volatile FrameListener listener;
    private volatile boolean capturing;

    private long frameIntervalMs;
    private long lastFrameMs;

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    /**
     * Entry point of the startForegroundService() launch. The notification is posted by the very
     * first statements of this method, before the intent is read or anything is validated: the
     * platform gives this process about five seconds to become foreground and otherwise kills it
     * with a RemoteServiceException, so no read, check or capture may come first.
     *
     * <p>The return value is always {@link #START_NOT_STICKY}. A re-delivered, null intent would
     * have no consent token to resume with, and a sticky restart would therefore only spin up a
     * foreground service that can never capture.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The foreground service type overload exists from API 29, and from Android 10 on the
            // projection is only handed out to a service of type mediaProjection.
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            // Type came with API 29; before that the two-argument overload is the only one.
            startForeground(NOTIFICATION_ID, notification);
        }
        if (foregroundStarted) {
            Log.i(LOG_TAG, "startForeground repeated, notification " + NOTIFICATION_ID
                    + " already posted, startId=" + startId);
        } else {
            foregroundStarted = true;
            Log.i(LOG_TAG, "foreground started, type=mediaProjection, notification "
                    + NOTIFICATION_ID + ", startId=" + startId);
        }

        if (intent == null) {
            Log.e(LOG_TAG, "no intent, nothing to capture, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        int code = intent.getIntExtra(MainActivity.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = readProjectionData(intent);
        if (code != Activity.RESULT_OK || data == null) {
            // Cancelled consent, or an OEM dialog that returned without usable data. Capturing is
            // impossible without the token, so the service ends instead of staying foreground.
            Log.e(LOG_TAG, "media projection consent missing: resultCode=" + code
                    + " data=" + (data != null) + ", stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(LOG_TAG, "media projection consent accepted: resultCode=" + code);
        setProjectionResult(code, data);

        int[] size = screenSizePixels();
        Log.i(LOG_TAG, "starting capture at " + size[0] + "x" + size[1] + " fps=" + DEFAULT_FPS);
        startCapture(size[0], size[1], DEFAULT_FPS);
        // startCapture logs its own reason on failure; this line only records the outcome.
        Log.i(LOG_TAG, "capture requested, capturing=" + isCapturing());
        return START_NOT_STICKY;
    }

    /**
     * The consent result is a Parcelable whose class is only known from the contract, so it has to
     * be read with the typed overload from API 33 on. Before that the typed overload does not
     * exist, and the raw one is the only way to get the value back.
     */
    @SuppressWarnings("deprecation")
    private static Intent readProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(MainActivity.EXTRA_RESULT_DATA, Intent.class);
        }
        return (Intent) intent.getParcelableExtra(MainActivity.EXTRA_RESULT_DATA);
    }

    /**
     * Real panel size in pixels. WindowMetrics is the non-deprecated source from API 30 on and
     * needs no window, which suits a service; DisplayMetrics is the always available fallback
     * because {@code Display.getRealSize()} and {@code getRealMetrics()} are deprecated.
     */
    private int[] screenSizePixels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager windowManager =
                    (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null) {
                try {
                    Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        return new int[] {bounds.width(), bounds.height()};
                    }
                    Log.e(LOG_TAG, "window metrics reported an empty bounds " + bounds);
                } catch (RuntimeException e) {
                    // Some vendor builds refuse window metrics for a service context.
                    Log.e(LOG_TAG, "window metrics unavailable, using display metrics", e);
                }
            }
        }
        if (getResources() != null) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            if (metrics != null && metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return new int[] {metrics.widthPixels, metrics.heightPixels};
            }
        }
        Log.e(LOG_TAG, "no display size available, falling back to " + FALLBACK_WIDTH + "x"
                + FALLBACK_HEIGHT);
        return new int[] {FALLBACK_WIDTH, FALLBACK_HEIGHT};
    }

    /** Channels exist from API 26 only; creating the same id twice is a no-op for the platform. */
    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            // startForeground still succeeds on a device without a notification manager, so this
            // is logged but not treated as fatal.
            Log.e(LOG_TAG, "no NotificationManager, cannot create channel " + CHANNEL_ID);
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Screen capture",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while this device's screen is being shared");
        manager.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            // Before channels the builder has no channel argument.
            builder = new Notification.Builder(this);
        }
        return builder
                // A system icon keeps this independent of the app's drawables.
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Screen capture active")
                .setContentText("This device's screen is being shared")
                .setOngoing(true)
                .setContentIntent(openAppIntent())
                .build();
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // FLAG_IMMUTABLE exists from API 23 and is mandatory for any PendingIntent from API 31
            // on, so the branch is only there for a build with a lower minSdk.
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, pendingFlags);
        if (pending == null) {
            Log.e(LOG_TAG, "notification tap intent could not be built");
        }
        return pending;
    }

    /** Idempotent so the failure paths and onDestroy can both call it without posting twice. */
    private void removeForegroundNotification() {
        if (!foregroundStarted) return;
        foregroundStarted = false;
        // STOP_FOREGROUND_REMOVE exists from API 24, which is this app's minSdk, so the deprecated
        // boolean overload is never needed.
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(LOG_TAG, "foreground stopped, notification " + NOTIFICATION_ID + " removed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "service destroying, releasing capture");
        stopCapture();
        removeForegroundNotification();
        super.onDestroy();
    }

    /** Keeps the consent result until {@link #startCapture} runs; the token is consumed once. */
    public void setProjectionResult(int resultCode, Intent data) {
        synchronized (lock) {
            this.resultCode = resultCode;
            this.resultData = data;
        }
    }

    public void setFrameListener(FrameListener listener) {
        this.listener = listener;
    }

    public boolean isCapturing() {
        return capturing;
    }

    public void startCapture(int width, int height, int fps) {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "startCapture: bad size " + width + "x" + height);
            return;
        }

        synchronized (lock) {
            if (capturing) return;
            if (resultData == null) {
                Log.w(TAG, "startCapture: no MediaProjection consent result");
                return;
            }
            MediaProjection mp;
            try {
                mp = projectionManager.getMediaProjection(resultCode, resultData);
            } catch (RuntimeException e) {
                // SecurityException here means the foreground service requirement is not met.
                Log.e(TAG, "getMediaProjection rejected", e);
                return;
            }
            if (mp == null) {
                Log.e(TAG, "getMediaProjection returned null");
                return;
            }
            // The consent data can be spent only once.
            resultData = null;

            int frameRate = fps > 0 ? fps : DEFAULT_FPS;
            frameIntervalMs = 1000L / frameRate;
            lastFrameMs = 0L;

            captureThread = new HandlerThread("screen-capture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            projection = mp;
            // API 34 throws IllegalStateException when createVirtualDisplay() runs without a
            // registered callback, so it is always registered.
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    // Runs on the capture handler when the user revokes screen sharing.
                    Log.w(TAG, "projection stopped by the system");
                    releaseCapture(false);
                }
            }, captureHandler);

            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES);
            reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
            display = mp.createVirtualDisplay(DISPLAY_NAME, width, height,
                    getResources().getDisplayMetrics().densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, captureHandler);
            if (display == null) {
                Log.e(TAG, "createVirtualDisplay failed");
                releaseCapture(true);
                return;
            }
            capturing = true;
        }
    }

    public void stopCapture() {
        Handler handler;
        synchronized (lock) {
            handler = captureHandler;
            if (handler == null) {
                resultData = null;
                return;
            }
        }
        if (!handler.post(() -> releaseCapture(true))) {
            // The looper already quit; release from this thread instead of leaking the projection.
            releaseCapture(true);
        }
    }

    /**
     * @param stopProjection false when the projection is already stopping (onStop callback), where
     *                       calling {@link MediaProjection#stop()} again would re-enter the callback
     */
    private void releaseCapture(boolean stopProjection) {
        HandlerThread thread;
        synchronized (lock) {
            capturing = false;
            if (display != null) {
                display.release();
                display = null;
            }
            if (reader != null) {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
                reader = null;
            }
            MediaProjection mp = projection;
            projection = null;
            if (mp != null && stopProjection) {
                mp.stop();
            }
            thread = captureThread;
            captureThread = null;
            captureHandler = null;
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private void onImageAvailable(ImageReader source) {
        if (!capturing) return;
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;

            long ptsMs = image.getTimestamp() / 1_000_000L;
            if (ptsMs == 0L) {
                // Some HALs leave the timestamp unset; falling back keeps the fps gate open.
                ptsMs = SystemClock.uptimeMillis();
            }
            // MediaProjection mirrors at the panel refresh rate and has no fps setting, so the
            // requested rate is enforced by dropping frames before they are copied.
            if (ptsMs - lastFrameMs < frameIntervalMs) return;
            lastFrameMs = ptsMs;

            byte[] rgba = copyRgba(image);
            FrameListener target = listener;
            if (rgba != null && target != null) {
                target.onFrame(rgba, image.getWidth(), image.getHeight(), ptsMs);
            }
        } catch (IllegalStateException e) {
            // Every buffer is still held, which happens when close() was skipped earlier.
            Log.w(TAG, "frame dropped: " + e.getMessage());
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private static byte[] copyRgba(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        if (pixelStride != 4) {
            // RGBA_8888 is always 4 bytes per pixel; anything else means the format is not RGBA.
            Log.w(TAG, "unexpected pixel stride " + pixelStride);
            return null;
        }

        int rowBytes = width * 4;
        byte[] out = new byte[rowBytes * height];
        if (rowStride == rowBytes) {
            int count = Math.min(buffer.remaining(), out.length);
            buffer.get(out, 0, count);
            return out;
        }
        // The graphics buffer pads rows to rowStride, so only rowBytes of each row are pixels.
        for (int y = 0; y < height; y++) {
            int start = y * rowStride;
            if (start + rowBytes > buffer.limit()) break;
            buffer.position(start);
            buffer.get(out, y * rowBytes, rowBytes);
        }
        return out;
    }
}
