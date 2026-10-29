package com.rustdesk.java.host;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * EGL bridge between raw RGBA frames and a MediaCodec input Surface.
 *
 * <p>The uploader owns an EGL display, context and window surface. Every
 * {@link #draw} uploads one top-row-first RGBA_8888 frame into a reused
 * texture and renders a full-screen quad into the encoder surface.</p>
 *
 * <p>Public methods are synchronized and do not require a specific thread.
 * A context is made current only for the duration of a call, because EGL
 * current-context state is per thread.</p>
 */
public final class GlFrameUploader {

    /**
     * EGL_RECORDABLE_ANDROID is not exposed by EGL14. It tells the driver the
     * surface is consumed by MediaCodec instead of a display composition path.
     */
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
            + "attribute vec2 aTexCoord;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "  vTexCoord = aTexCoord;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
            + "varying vec2 vTexCoord;\n"
            + "uniform sampler2D uTexture;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uTexture, vTexCoord);\n"
            + "}\n";

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;
    private boolean displayInitialized;
    private boolean initialized;
    private boolean presentationTimeSupported;

    private int surfaceWidth;
    private int surfaceHeight;

    private int program;
    private int texture;
    private int aPosition = -1;
    private int aTexCoord = -1;
    private int uTexture = -1;
    private int textureWidth;
    private int textureHeight;

    private FloatBuffer positionBuffer;
    private FloatBuffer texCoordBuffer;
    private ByteBuffer uploadBuffer;

    public GlFrameUploader() {
    }

    /**
     * Creates an EGL context bound to the encoder input surface.
     *
     * @throws IllegalArgumentException when the surface is null or invalid, or
     *                                  when a dimension is not positive
     * @throws IllegalStateException    when EGL or GL setup fails, or when this
     *                                  instance already owns resources
     */
    public synchronized void init(Surface encoderInputSurface, int width, int height) {
        if (initialized || hasEglResources()) {
            throw new IllegalStateException("GlFrameUploader is already initialized");
        }
        if (encoderInputSurface == null || !encoderInputSurface.isValid()) {
            throw new IllegalArgumentException("encoder input surface is null or invalid");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid frame size " + width + "x" + height);
        }

        RuntimeException failure = null;
        try {
            initInternal(encoderInputSurface, width, height);
        } catch (RuntimeException e) {
            failure = e;
        }
        if (failure != null) {
            try {
                releaseLocked();
            } catch (RuntimeException cleanupFailure) {
                // The setup error is the useful one; keep cleanup diagnostics too.
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Uploads one tightly packed RGBA_8888 frame and submits it to the encoder.
     *
     * @throws IllegalArgumentException when the frame is null, a dimension is
     *                                  not positive, or the array is too small
     * @throws IllegalStateException    when this instance is not initialized or
     *                                  when EGL/GL reports an error
     */
    public synchronized void draw(byte[] rgba, int width, int height, long ptsNs) {
        if (!initialized || !hasEglResources()) {
            throw new IllegalStateException("GlFrameUploader is not initialized");
        }
        if (rgba == null) {
            throw new IllegalArgumentException("rgba frame is null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid frame size " + width + "x" + height);
        }
        long required = (long) width * (long) height * 4L;
        if (required > Integer.MAX_VALUE || rgba.length < (int) required) {
            throw new IllegalArgumentException(
                    "rgba frame has " + rgba.length + " bytes, need " + required);
        }

        RuntimeException failure = null;
        try {
            makeCurrent();
            uploadFrame(rgba, width, height, (int) required);
            renderFrame();

            if (presentationTimeSupported) {
                // Presentation time applies to the next swap, so set it first.
                if (!EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsNs)) {
                    throw eglFailure("eglPresentationTimeANDROID");
                }
                checkEgl("eglPresentationTimeANDROID");
            }

            // The encoder consumes a frame only when the EGL surface is swapped.
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                throw eglFailure("eglSwapBuffers");
            }
            checkEgl("eglSwapBuffers");
        } catch (RuntimeException e) {
            failure = e;
        }

        try {
            unbindCurrent();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Releases all EGL and GL objects. Safe to call repeatedly.
     */
    public synchronized void release() {
        releaseLocked();
    }

    /**
     * True between a successful {@link #init} and the next {@link #release}.
     */
    public synchronized boolean isReady() {
        return initialized;
    }

    private void initInternal(Surface encoderInputSurface, int width, int height) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (!hasDisplay(eglDisplay)) {
            eglDisplay = null;
            throw eglFailure("eglGetDisplay");
        }

        int[] major = new int[1];
        int[] minor = new int[1];
        if (!EGL14.eglInitialize(eglDisplay, major, 0, minor, 0)) {
            throw eglFailure("eglInitialize");
        }
        displayInitialized = true;
        checkEgl("eglInitialize");

        if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
            throw eglFailure("eglBindAPI");
        }

        int[] configAttrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] configCount = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, configCount, 0)) {
            throw eglFailure("eglChooseConfig");
        }
        if (configCount[0] < 1 || configs[0] == null) {
            throw new IllegalStateException(
                    "no RGBA8888 ES2 config with EGL_RECORDABLE_ANDROID");
        }
        EGLConfig config = configs[0];

        int[] contextAttrs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
        if (!hasContext(eglContext)) {
            eglContext = null;
            throw eglFailure("eglCreateContext");
        }

        eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, config, encoderInputSurface, null, 0);
        if (!hasSurface(eglSurface)) {
            eglSurface = null;
            throw eglFailure("eglCreateWindowSurface");
        }
        makeCurrent();

        int[] value = new int[1];
        if (!EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, value, 0)) {
            throw eglFailure("eglQuerySurface(EGL_WIDTH)");
        }
        surfaceWidth = value[0];
        if (!EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, value, 0)) {
            throw eglFailure("eglQuerySurface(EGL_HEIGHT)");
        }
        surfaceHeight = value[0];
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            throw new IllegalStateException(
                    "invalid EGL surface size " + surfaceWidth + "x" + surfaceHeight);
        }

        String extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS);
        if (extensions == null) {
            // A null result can also mean that the driver advertises no extensions.
            checkEgl("eglQueryString(EGL_EXTENSIONS)");
        }
        presentationTimeSupported = extensions != null
                && hasExtension(extensions, "EGL_ANDROID_presentation_time");

        createGlResources(width, height);
        checkGl("creating GL resources");

        unbindCurrent();
        initialized = true;
    }

    private void createGlResources(int width, int height) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexture = GLES20.glGetUniformLocation(program, "uTexture");
        if (aPosition < 0 || aTexCoord < 0 || uTexture < 0) {
            throw new IllegalStateException("shader program is missing an attribute or uniform");
        }

        positionBuffer = floatBuffer(new float[]{
                -1.0f, -1.0f,
                1.0f, -1.0f,
                -1.0f, 1.0f,
                1.0f, 1.0f
        });
        // Source row 0 is the top row. GL texture t=0 is row 0 and clip-space
        // y=+1 is the top, so the top vertices sample t=0 and keep it upright.
        texCoordBuffer = floatBuffer(new float[]{
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
        });

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        texture = textures[0];
        if (texture == 0) {
            throw glFailure("glGenTextures", GLES20.glGetError());
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        checkGl("allocating RGBA texture");
        textureWidth = width;
        textureHeight = height;
    }

    private void uploadFrame(byte[] rgba, int width, int height, int required) {
        if (uploadBuffer == null || uploadBuffer.capacity() < required) {
            uploadBuffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder());
        }
        uploadBuffer.clear();
        uploadBuffer.put(rgba, 0, required);
        uploadBuffer.flip();

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (width != textureWidth || height != textureHeight) {
            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, uploadBuffer);
            checkGl("reallocating RGBA texture");
            textureWidth = width;
            textureHeight = height;
        } else {
            GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, uploadBuffer);
            checkGl("uploading RGBA texture");
        }
    }

    private void renderFrame() {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(uTexture, 0);

        positionBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(
                aPosition, 2, GLES20.GL_FLOAT, false, 8, positionBuffer);

        texCoordBuffer.position(0);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(
                aTexCoord, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGl("drawing frame");
    }

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw eglFailure("eglMakeCurrent");
        }
        checkEgl("eglMakeCurrent");
    }

    private void unbindCurrent() {
        if (!hasDisplay(eglDisplay)) {
            return;
        }
        if (!EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)) {
            throw eglFailure("eglMakeCurrent(release)");
        }
        checkEgl("eglMakeCurrent(release)");
    }

    private void releaseLocked() {
        EGLDisplay display = eglDisplay;
        EGLContext context = eglContext;
        EGLSurface surface = eglSurface;
        int textureId = texture;
        int programId = program;
        boolean wasDisplayInitialized = displayInitialized;

        initialized = false;
        displayInitialized = false;
        presentationTimeSupported = false;
        eglDisplay = null;
        eglContext = null;
        eglSurface = null;
        texture = 0;
        program = 0;
        aPosition = -1;
        aTexCoord = -1;
        uTexture = -1;
        positionBuffer = null;
        texCoordBuffer = null;
        uploadBuffer = null;
        textureWidth = 0;
        textureHeight = 0;
        surfaceWidth = 0;
        surfaceHeight = 0;

        if (!hasDisplay(display)) {
            return;
        }

        IllegalStateException failure = null;
        boolean current = false;
        if (wasDisplayInitialized && hasContext(context) && hasSurface(surface)) {
            if (EGL14.eglMakeCurrent(display, surface, surface, context)) {
                current = true;
                if (textureId != 0) {
                    GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                }
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId);
                }
                int error = GLES20.glGetError();
                if (error != GLES20.GL_NO_ERROR) {
                    failure = glFailure("deleting GL resources", error);
                }
            } else {
                failure = eglFailure("eglMakeCurrent(release)");
            }
        }

        if (current) {
            if (!EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT)) {
                failure = addFailure(failure, eglFailure("eglMakeCurrent(release)"));
            }
        }
        if (hasSurface(surface)) {
            if (!EGL14.eglDestroySurface(display, surface)) {
                failure = addFailure(failure, eglFailure("eglDestroySurface"));
            }
        }
        if (hasContext(context)) {
            if (!EGL14.eglDestroyContext(display, context)) {
                failure = addFailure(failure, eglFailure("eglDestroyContext"));
            }
        }
        if (wasDisplayInitialized) {
            if (!EGL14.eglTerminate(display)) {
                failure = addFailure(failure, eglFailure("eglTerminate"));
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader;
        try {
            fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        } catch (RuntimeException e) {
            GLES20.glDeleteShader(vertexShader);
            throw e;
        }
        int created = GLES20.glCreateProgram();
        if (created == 0) {
            int error = GLES20.glGetError();
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            throw glFailure("glCreateProgram", error);
        }

        GLES20.glAttachShader(created, vertexShader);
        GLES20.glAttachShader(created, fragmentShader);
        GLES20.glLinkProgram(created);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(created);
            GLES20.glDeleteProgram(created);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            throw new IllegalStateException("shader program link failed: " + log);
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        checkGl("linking shader program");
        return created;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            throw glFailure("glCreateShader", GLES20.glGetError());
        }
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        checkGl("compiling shader");
        return shader;
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static boolean hasExtension(String extensions, String name) {
        int start = 0;
        while (start < extensions.length()) {
            while (start < extensions.length() && extensions.charAt(start) == ' ') {
                start++;
            }
            int end = extensions.indexOf(' ', start);
            if (end < 0) {
                end = extensions.length();
            }
            if (end - start == name.length()
                    && extensions.regionMatches(start, name, 0, name.length())) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    private boolean hasEglResources() {
        return hasDisplay(eglDisplay) || hasContext(eglContext) || hasSurface(eglSurface);
    }

    private static boolean hasDisplay(EGLDisplay display) {
        return display != null && display != EGL14.EGL_NO_DISPLAY;
    }

    private static boolean hasContext(EGLContext context) {
        return context != null && context != EGL14.EGL_NO_CONTEXT;
    }

    private static boolean hasSurface(EGLSurface surface) {
        return surface != null && surface != EGL14.EGL_NO_SURFACE;
    }

    private static void checkEgl(String operation) {
        int error = EGL14.eglGetError();
        if (error != EGL14.EGL_SUCCESS) {
            throw eglFailure(operation, error);
        }
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw glFailure(operation, error);
        }
    }

    private static IllegalStateException eglFailure(String operation) {
        return eglFailure(operation, EGL14.eglGetError());
    }

    private static IllegalStateException eglFailure(String operation, int error) {
        return new IllegalStateException(
                operation + " failed, EGL error 0x" + Integer.toHexString(error));
    }

    private static IllegalStateException glFailure(String operation, int error) {
        return new IllegalStateException(
                operation + " failed, GL error 0x" + Integer.toHexString(error));
    }

    private static IllegalStateException addFailure(
            IllegalStateException first, IllegalStateException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
