// JVM-only regression suite for session.VideoSettings, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

import com.rustdesk.java.session.VideoSettings;

import hbb.MessageOuterClass.ImageQuality;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.Misc;
import hbb.MessageOuterClass.OptionMessage;

/**
 * Pins the stream-parameter encoding of {@link VideoSettings}: frame-rate/scale clamping, the
 * image-quality selection, the immutable with*() copies, and the four messages the session
 * sends (login options, runtime fps, runtime scale, runtime quality).
 */
public final class VideoSettingsTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("VideoSettings (pure JVM, protobuf only, no android.jar)");
        System.out.println();

        testDefaults();
        testFpsClamp();
        testScaleClamp();
        testQuality();
        testLockFps();
        testWithMethods();
        testOptionMessage();
        testFpsMessage();
        testScaleMessage();
        testQualityMessage();
        testToString();

        System.out.println();
        System.out.println("checks: " + checks);
        if (failures == 0) {
            System.out.println("RESULT: ALL PASSED");
        } else {
            System.out.println("RESULT: FAILED (" + failures + " of " + checks + " checks)");
            System.exit(1);
        }
    }

    private static void testDefaults() {
        section("defaults()");
        VideoSettings defaults = VideoSettings.defaults();
        check(defaults.fps() == VideoSettings.DEFAULT_FPS,
                "defaults().fps() == " + VideoSettings.DEFAULT_FPS + " (got " + defaults.fps() + ")");
        check(defaults.scalePercent() == VideoSettings.NATIVE_SCALE,
                "defaults().scalePercent() == 100 (got " + defaults.scalePercent() + ")");
        check(defaults.quality() == ImageQuality.Balanced,
                "defaults().quality() is Balanced (got " + defaults.quality() + ")");
        check(!defaults.hasCustomScale(), "defaults() keeps the native stream size");
    }

    private static void testFpsClamp() {
        section("constructor clamps fps");
        int below = new VideoSettings(1, 100).fps();
        check(below == VideoSettings.MIN_FPS,
                "fps 1 clamps to " + VideoSettings.MIN_FPS + " (got " + below + ")");
        int above = new VideoSettings(144, 100).fps();
        check(above == VideoSettings.MAX_FPS,
                "fps 144 clamps to " + VideoSettings.MAX_FPS + " (got " + above + ")");
        check(new VideoSettings(60, 100).fps() == 60
                        && new VideoSettings(90, 100).fps() == 90
                        && new VideoSettings(120, 100).fps() == 120,
                "60 / 90 / 120 fps are kept as selected");
    }

    private static void testScaleClamp() {
        section("constructor clamps the stream scale");
        int below = new VideoSettings(30, 1).scalePercent();
        check(below == VideoSettings.MIN_SCALE,
                "scale 1 clamps to " + VideoSettings.MIN_SCALE + " (got " + below + ")");
        int zero = new VideoSettings(30, 0).scalePercent();
        check(zero == VideoSettings.MIN_SCALE,
                "scale 0 clamps to " + VideoSettings.MIN_SCALE + " (got " + zero + ")");
        int above = new VideoSettings(30, 120).scalePercent();
        check(above == VideoSettings.NATIVE_SCALE,
                "scale 120 clamps to 100 (got " + above + ")");
        check(new VideoSettings(30, 50).hasCustomScale(), "scale 50 reports a custom scale");
        check(!new VideoSettings(30, 100).hasCustomScale(), "scale 100 reports native");
    }

    private static void testQuality() {
        section("quality selection");
        check(new VideoSettings(30, 100, ImageQuality.Low).quality() == ImageQuality.Low,
                "explicit Low is kept");
        check(new VideoSettings(30, 100, ImageQuality.Best).quality() == ImageQuality.Best,
                "explicit Best is kept");
        check(new VideoSettings(30, 100, ImageQuality.NotSet).quality() == ImageQuality.Balanced,
                "NotSet falls back to Balanced");
        check(new VideoSettings(30, 100, null).quality() == ImageQuality.Balanced,
                "null falls back to Balanced");
    }

    private static void testLockFps() {
        section("frame-rate lock switch");
        check(!VideoSettings.defaults().lockFps(), "defaults() leaves the frame rate unlocked");

        VideoSettings base = new VideoSettings(30, 50, ImageQuality.Low, false);
        VideoSettings locked = base.withLockFps(true);
        check(locked != base, "withLockFps() returns a new instance");
        check(locked.lockFps(), "withLockFps(true) locks the frame rate");
        check(!base.lockFps(), "original stays unlocked");
        check(locked.fps() == 30 && locked.scalePercent() == 50 && locked.quality() == ImageQuality.Low,
                "withLockFps keeps the other settings");

        check(!VideoSettings.defaults().toOptionMessage().getLockFps()
                        .equals(OptionMessage.BoolOption.Yes),
                "unlocked login option does not request the lock");
        check(new VideoSettings(30, 50, ImageQuality.Low, true).toOptionMessage().getLockFps()
                        == OptionMessage.BoolOption.Yes,
                "locked login option requests the lock");

        Message lockMsg = new VideoSettings(30, 50, ImageQuality.Low, true).toFpsLockMessage();
        check(lockMsg.hasMisc() && lockMsg.getMisc().hasOption(),
                "lock message carries misc.option");
        check(lockMsg.getMisc().getOption().getLockFps() == OptionMessage.BoolOption.Yes,
                "lock message sets lock_fps = Yes");
        check(lockMsg.getMisc().getOption().getCustomFps() == 0,
                "lock message leaves custom_fps unset");
    }

    private static void testWithMethods() {
        section("withFps()/withScale()/withQuality() return copies");
        VideoSettings base = new VideoSettings(30, 75, ImageQuality.Low);

        VideoSettings faster = base.withFps(60);
        check(faster != base, "withFps() returns a new instance");
        check(faster.fps() == 60, "withFps(60).fps() == 60 (got " + faster.fps() + ")");
        check(faster.scalePercent() == 75, "withFps keeps the scale (got "
                + faster.scalePercent() + ")");
        check(faster.quality() == ImageQuality.Low, "withFps keeps the quality");
        check(base.fps() == 30, "original fps stays 30 (got " + base.fps() + ")");

        VideoSettings smaller = base.withScale(33);
        check(smaller != base, "withScale() returns a new instance");
        check(smaller.scalePercent() == 33, "withScale(33) keeps the new scale (got "
                + smaller.scalePercent() + ")");
        check(smaller.fps() == 30, "withScale keeps the fps (got " + smaller.fps() + ")");
        check(smaller.quality() == ImageQuality.Low, "withScale keeps the quality");

        VideoSettings better = base.withQuality(ImageQuality.Best);
        check(better != base, "withQuality() returns a new instance");
        check(better.quality() == ImageQuality.Best, "withQuality(Best) keeps the new quality");
        check(better.fps() == 30 && better.scalePercent() == 75,
                "withQuality keeps fps and scale");
        check(base.quality() == ImageQuality.Low, "original quality stays Low");
    }

    private static void testOptionMessage() {
        section("toOptionMessage()");
        VideoSettings settings = new VideoSettings(45, 100, ImageQuality.Best);
        check(settings.toOptionMessage().getCustomFps() == 45,
                "option.custom_fps == 45 (got " + settings.toOptionMessage().getCustomFps() + ")");
        check(settings.toOptionMessage().getImageQuality() == ImageQuality.Best,
                "option.image_quality is Best (got "
                        + settings.toOptionMessage().getImageQuality() + ")");
        check(settings.toOptionMessage().getCustomImageQuality() == 0,
                "option.custom_image_quality == 0 (got "
                        + settings.toOptionMessage().getCustomImageQuality() + ")");
        check(settings.toOptionMessage().getCustomVideoScale() == 0,
                "native scale leaves custom_video_scale unset (got "
                        + settings.toOptionMessage().getCustomVideoScale() + ")");

        VideoSettings scaled = new VideoSettings(30, 50, ImageQuality.Low);
        check(scaled.toOptionMessage().getCustomVideoScale() == 50,
                "option.custom_video_scale == 50 (got "
                        + scaled.toOptionMessage().getCustomVideoScale() + ")");
        check(scaled.toOptionMessage().getImageQuality() == ImageQuality.Low,
                "option carries the chosen quality");
    }

    private static void testFpsMessage() {
        section("toFpsMessage()");
        Message message = new VideoSettings(50, 75, ImageQuality.Best).toFpsMessage();
        check(message.hasMisc(), "message carries misc (got " + message.getUnionCase() + ")");
        check(message.getMisc().hasOption(),
                "misc carries option (got " + message.getMisc().getUnionCase() + ")");
        check(message.getMisc().getOption().getCustomFps() == 50,
                "option.custom_fps == 50 (got "
                        + message.getMisc().getOption().getCustomFps() + ")");
        check(message.getMisc().getOption().getImageQuality() == ImageQuality.Best,
                "option carries the quality");
    }

    private static void testScaleMessage() {
        section("toScaleMessage()");
        Message scaled = new VideoSettings(30, 25).toScaleMessage();
        check(scaled.hasMisc(), "message carries misc (got " + scaled.getUnionCase() + ")");
        Misc misc = scaled.getMisc();
        check(misc.hasOption(), "misc carries option (got " + misc.getUnionCase() + ")");
        check(misc.getOption().getCustomVideoScale() == 25,
                "custom_video_scale == 25 (got " + misc.getOption().getCustomVideoScale() + ")");
        // 100 must be explicit so a live session can return to the native size.
        Message restored = new VideoSettings(30, 100).toScaleMessage();
        check(restored.getMisc().getOption().getCustomVideoScale() == 100,
                "restoring native sends custom_video_scale == 100 (got "
                        + restored.getMisc().getOption().getCustomVideoScale() + ")");
    }

    private static void testQualityMessage() {
        section("toQualityMessage()");
        Message message = new VideoSettings(30, 50, ImageQuality.Low).toQualityMessage();
        check(message.hasMisc(), "message carries misc (got " + message.getUnionCase() + ")");
        check(message.getMisc().hasOption(),
                "misc carries option (got " + message.getMisc().getUnionCase() + ")");
        check(message.getMisc().getOption().getImageQuality() == ImageQuality.Low,
                "option.image_quality is Low (got "
                        + message.getMisc().getOption().getImageQuality() + ")");
        check(message.getMisc().getOption().getCustomFps() == 0,
                "quality message leaves custom_fps unset (got "
                        + message.getMisc().getOption().getCustomFps() + ")");
    }

    private static void testToString() {
        section("toString()");
        String defaults = VideoSettings.defaults().toString();
        check(defaults.contains("fps=30"), "native text contains fps=30 (got " + defaults + ")");
        check(defaults.contains("scale=100%"),
                "native text contains scale=100% (got " + defaults + ")");
        check(defaults.contains("quality=Balanced"),
                "native text contains quality=Balanced (got " + defaults + ")");
        String custom = new VideoSettings(45, 50, ImageQuality.Best).toString();
        check(custom.contains("fps=45"), "custom text contains fps=45 (got " + custom + ")");
        check(custom.contains("scale=50%"), "custom text contains scale=50% (got " + custom + ")");
        check(custom.contains("quality=Best"),
                "custom text contains quality=Best (got " + custom + ")");
    }

    private static void section(String title) {
        System.out.println("-- " + title);
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
