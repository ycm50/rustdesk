package com.rustdesk.java.session;

import hbb.MessageOuterClass.ImageQuality;
import hbb.MessageOuterClass.Message;
import hbb.MessageOuterClass.Misc;
import hbb.MessageOuterClass.OptionMessage;

/**
 * Video options of a session: frame rate, stream scale, image quality and whether the frame rate
 * is locked.
 *
 * <p>The scale keeps the captured size at 100; lower values make the host encode and send a
 * smaller image without touching the controlled desktop's own resolution. The quality selects
 * the host's bitrate/quality ratio. When the frame rate is locked, the host never lowers it;
 * otherwise its adaptive controller may trade frame rate for bandwidth down to half the
 * requested value.</p>
 */
public final class VideoSettings {

    public static final int DEFAULT_FPS = 30;
    public static final int MIN_FPS = 5;
    public static final int MAX_FPS = 120;

    /** 100 means "encode at the captured size"; lower values downscale the stream. */
    public static final int NATIVE_SCALE = 100;
    public static final int MIN_SCALE = 10;

    private static final ImageQuality DEFAULT_QUALITY = ImageQuality.Balanced;

    private final int fps;
    private final int scalePercent;
    private final ImageQuality quality;
    private final boolean lockFps;

    public VideoSettings(int fps, int scalePercent) {
        this(fps, scalePercent, DEFAULT_QUALITY, false);
    }

    public VideoSettings(int fps, int scalePercent, ImageQuality quality) {
        this(fps, scalePercent, quality, false);
    }

    public VideoSettings(int fps, int scalePercent, ImageQuality quality, boolean lockFps) {
        this.fps = clampFps(fps);
        this.scalePercent = clampScale(scalePercent);
        this.quality = quality == null || quality == ImageQuality.NotSet
                ? DEFAULT_QUALITY : quality;
        this.lockFps = lockFps;
    }

    public static VideoSettings defaults() {
        return new VideoSettings(DEFAULT_FPS, NATIVE_SCALE);
    }

    public VideoSettings copy() {
        return new VideoSettings(fps, scalePercent, quality, lockFps);
    }

    public int fps() {
        return fps;
    }

    public int scalePercent() {
        return scalePercent;
    }

    public ImageQuality quality() {
        return quality;
    }

    /** True when the viewer asked the host to never lower the frame rate. */
    public boolean lockFps() {
        return lockFps;
    }

    /** True when the stream is smaller than the captured image. */
    public boolean hasCustomScale() {
        return scalePercent < NATIVE_SCALE;
    }

    public VideoSettings withFps(int fps) {
        return new VideoSettings(fps, scalePercent, quality, lockFps);
    }

    public VideoSettings withScale(int scalePercent) {
        return new VideoSettings(fps, scalePercent, quality, lockFps);
    }

    public VideoSettings withQuality(ImageQuality quality) {
        return new VideoSettings(fps, scalePercent, quality, lockFps);
    }

    public VideoSettings withLockFps(boolean lockFps) {
        return new VideoSettings(fps, scalePercent, quality, lockFps);
    }

    /**
     * Option message sent at login. The frame rate, quality and lock switch are always carried;
     * a native scale is left at its default so an untouched session does not ask for scaling.
     */
    public OptionMessage toOptionMessage() {
        OptionMessage.Builder builder = OptionMessage.newBuilder()
                .setCustomFps(fps)
                .setImageQuality(quality)
                .setLockFps(lockFps
                        ? OptionMessage.BoolOption.Yes
                        : OptionMessage.BoolOption.No);
        if (hasCustomScale()) {
            builder.setCustomVideoScale(scalePercent);
        }
        return builder.build();
    }

    /** Runtime frame-rate change: {@code Message.misc.option.custom_fps}. */
    public Message toFpsMessage() {
        Misc misc = Misc.newBuilder().setOption(toOptionMessage()).build();
        return Message.newBuilder().setMisc(misc).build();
    }

    /**
     * Runtime scale change: {@code Message.misc.option.custom_video_scale}. Always carries the
     * current percentage so switching back to 100 restores the native size.
     */
    public Message toScaleMessage() {
        OptionMessage option = OptionMessage.newBuilder()
                .setCustomVideoScale(scalePercent)
                .build();
        return Message.newBuilder()
                .setMisc(Misc.newBuilder().setOption(option).build())
                .build();
    }

    /** Runtime quality change: {@code Message.misc.option.image_quality}. */
    public Message toQualityMessage() {
        OptionMessage option = OptionMessage.newBuilder()
                .setImageQuality(quality)
                .build();
        return Message.newBuilder()
                .setMisc(Misc.newBuilder().setOption(option).build())
                .build();
    }

    /** Runtime lock switch: {@code Message.misc.option.lock_fps}. */
    public Message toFpsLockMessage() {
        OptionMessage option = OptionMessage.newBuilder()
                .setLockFps(lockFps
                        ? OptionMessage.BoolOption.Yes
                        : OptionMessage.BoolOption.No)
                .build();
        return Message.newBuilder()
                .setMisc(Misc.newBuilder().setOption(option).build())
                .build();
    }

    @Override
    public String toString() {
        return "fps=" + fps + " scale=" + scalePercent + "% quality=" + quality
                + " lockFps=" + lockFps;
    }

    private static int clampFps(int fps) {
        if (fps < MIN_FPS) {
            return MIN_FPS;
        }
        if (fps > MAX_FPS) {
            return MAX_FPS;
        }
        return fps;
    }

    private static int clampScale(int scalePercent) {
        if (scalePercent < MIN_SCALE) {
            return MIN_SCALE;
        }
        if (scalePercent > NATIVE_SCALE) {
            return NATIVE_SCALE;
        }
        return scalePercent;
    }
}
