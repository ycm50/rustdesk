package com.rustdesk.java.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.rustdesk.java.session.VideoSettings;

import hbb.MessageOuterClass.ImageQuality;

/**
 * Frame-rate, stream-size and image-quality picker for the controlled peer.
 *
 * <p>Only collects the choice: the caller applies it to its session (and stores it), so this
 * dialog never touches the network. The stream scale shrinks the transmitted video; the image
 * quality selects the host's bitrate/quality target, around which its adaptive controller
 * trades frame rate.</p>
 */
public final class VideoSettingsDialog {

    /** Receives the chosen values on Apply; {@code scalePercent} is 25..100. */
    public interface Applier {
        void onApply(int fps, int scalePercent, ImageQuality quality, boolean lockFps);
    }

    /** Offered frame rates. The peer accepts a range, so a short list is enough here. */
    private static final int[] FPS_VALUES = {10, 15, 20, 24, 30, 45, 60};

    /** Offered stream scales, largest first. */
    private static final int[] SCALE_VALUES = {100, 75, 50, 33, 25};

    /** Offered image qualities; labels follow the protocol enum names. */
    private static final ImageQuality[] QUALITY_VALUES = {
            ImageQuality.Low, ImageQuality.Balanced, ImageQuality.Best
    };

    private VideoSettingsDialog() {}

    /**
     * Shows the picker.
     *
     * @param current       settings to preselect, null for the defaults
     * @param nativeWidth   captured desktop width, 0 when unknown; only used for the hints
     * @param nativeHeight  captured desktop height, 0 when unknown
     * @param applier       receives the choice when Apply is pressed
     */
    public static void show(Activity activity, VideoSettings current, int nativeWidth,
                            int nativeHeight, Applier applier) {
        if (activity == null || activity.isFinishing() || applier == null) {
            return;
        }
        VideoSettings settings = current == null ? VideoSettings.defaults() : current;

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16f * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView fpsLabel = new TextView(activity);
        fpsLabel.setText("Frame rate");
        layout.addView(fpsLabel);

        String[] fpsLabels = new String[FPS_VALUES.length];
        for (int i = 0; i < FPS_VALUES.length; i++) {
            fpsLabels[i] = FPS_VALUES[i] + " fps";
        }
        final Spinner fpsSpinner = new Spinner(activity);
        fpsSpinner.setAdapter(adapter(activity, fpsLabels));
        fpsSpinner.setSelection(nearestFpsIndex(settings.fps()));
        layout.addView(fpsSpinner);

        TextView scaleLabel = new TextView(activity);
        scaleLabel.setText("Stream resolution");
        layout.addView(scaleLabel);

        String[] scaleLabels = new String[SCALE_VALUES.length];
        for (int i = 0; i < SCALE_VALUES.length; i++) {
            scaleLabels[i] = scaleLabel(SCALE_VALUES[i], nativeWidth, nativeHeight);
        }
        final Spinner scaleSpinner = new Spinner(activity);
        scaleSpinner.setAdapter(adapter(activity, scaleLabels));
        scaleSpinner.setSelection(nearestScaleIndex(settings.scalePercent()));
        layout.addView(scaleSpinner);

        TextView qualityLabel = new TextView(activity);
        qualityLabel.setText("Image quality");
        layout.addView(qualityLabel);

        String[] qualityLabels = new String[QUALITY_VALUES.length];
        for (int i = 0; i < QUALITY_VALUES.length; i++) {
            qualityLabels[i] = qualityName(QUALITY_VALUES[i]);
        }
        final Spinner qualitySpinner = new Spinner(activity);
        qualitySpinner.setAdapter(adapter(activity, qualityLabels));
        qualitySpinner.setSelection(qualityIndex(settings.quality()));
        layout.addView(qualitySpinner);

        final CheckBox lockFps = new CheckBox(activity);
        lockFps.setText("Lock frame rate (never drop below the setting)");
        lockFps.setChecked(settings.lockFps());
        layout.addView(lockFps);

        TextView note = new TextView(activity);
        note.setText("Only the transmitted video is scaled; the controlled desktop keeps its "
                + "own resolution. With the lock off, the host may halve the frame rate on a "
                + "congested link.");
        layout.addView(note);

        new AlertDialog.Builder(activity)
                .setTitle("Video settings")
                .setView(layout)
                .setPositiveButton("Apply", (dialog, which) -> {
                    int fpsIndex = Math.max(0, fpsSpinner.getSelectedItemPosition());
                    int fps = FPS_VALUES[Math.min(fpsIndex, FPS_VALUES.length - 1)];
                    int scaleIndex = Math.max(0, scaleSpinner.getSelectedItemPosition());
                    int scale = SCALE_VALUES[Math.min(scaleIndex, SCALE_VALUES.length - 1)];
                    int qualityPick = Math.max(0, qualitySpinner.getSelectedItemPosition());
                    ImageQuality quality =
                            QUALITY_VALUES[Math.min(qualityPick, QUALITY_VALUES.length - 1)];
                    applier.onApply(fps, scale, quality, lockFps.isChecked());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static ArrayAdapter<String> adapter(Activity activity, String[] labels) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    /** "100% (native)" or "50% (~960x600)" when the captured size is known. */
    private static String scaleLabel(int percent, int nativeWidth, int nativeHeight) {
        if (percent >= VideoSettings.NATIVE_SCALE) {
            return percent + "% (native)";
        }
        if (nativeWidth <= 0 || nativeHeight <= 0) {
            return percent + "%";
        }
        int width = Math.max(2, nativeWidth * percent / 100) & ~1;
        int height = Math.max(2, nativeHeight * percent / 100) & ~1;
        return percent + "% (~" + width + "x" + height + ")";
    }

    private static String qualityName(ImageQuality quality) {
        switch (quality) {
            case Low:
                return "Low";
            case Best:
                return "Best";
            default:
                return "Balanced";
        }
    }

    /** Index of the offered scale closest to {@code percent}. */
    private static int nearestScaleIndex(int percent) {
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < SCALE_VALUES.length; i++) {
            int diff = Math.abs(SCALE_VALUES[i] - percent);
            if (diff < bestDiff) {
                best = i;
                bestDiff = diff;
            }
        }
        return best;
    }

    /** Index of the entry closest to {@code fps}; an exact match always wins. */
    private static int nearestFpsIndex(int fps) {
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < FPS_VALUES.length; i++) {
            int diff = Math.abs(FPS_VALUES[i] - fps);
            if (diff < bestDiff) {
                best = i;
                bestDiff = diff;
            }
        }
        return best;
    }

    private static int qualityIndex(ImageQuality quality) {
        for (int i = 0; i < QUALITY_VALUES.length; i++) {
            if (QUALITY_VALUES[i] == quality) return i;
        }
        return 1; // Balanced
    }
}
