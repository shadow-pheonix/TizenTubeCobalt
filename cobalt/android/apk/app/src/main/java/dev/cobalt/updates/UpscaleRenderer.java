package dev.cobalt.updates;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One external-OES draw per decoded frame. No readback or intermediate full-size texture. */
public final class UpscaleRenderer {
  public static final class NotNeeded extends RuntimeException {}
  private static final String TAG = "TizenTubeUpscaler";
  private final Surface output;
  private final HandlerThread thread = new HandlerThread("TizenTube-SGSR1");
  private final Handler handler;
  private final float[] matrix = new float[16];
  private final int[] dimensions = new int[2];
  private final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
      .asFloatBuffer();
  private volatile int width, height, mode;
  private volatile boolean closed;
  private Surface input;
  private SurfaceTexture texture;
  private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  private EGLContext context = EGL14.EGL_NO_CONTEXT;
  private EGLSurface window = EGL14.EGL_NO_SURFACE;
  private int program, textureId, position, transform, viewport, enabled;
  private int frames, stalls;
  private boolean overloaded;
  private long lastFrame;
  private Exception initializationError;
  private volatile Runnable failureHandler;

  private UpscaleRenderer(Surface output, int width, int height, int mode) {
    this.output = output; this.width = width; this.height = height; this.mode = mode;
    vertices.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
    thread.start(); handler = new Handler(thread.getLooper());
  }

  public static UpscaleRenderer create(Surface output, int width, int height, int mode) throws Exception {
    UpscaleRenderer renderer = new UpscaleRenderer(output, width, height, mode);
    CountDownLatch ready = new CountDownLatch(1);
    renderer.handler.post(() -> {
      try { if (!renderer.closed) renderer.initialize(); }
      catch (Exception error) { renderer.initializationError = error; renderer.destroy(); }
      finally { ready.countDown(); }
    });
    if (!ready.await(3, TimeUnit.SECONDS)) { renderer.close(); throw new IllegalStateException("GPU initialization timed out"); }
    if (renderer.initializationError != null) { renderer.close(); throw renderer.initializationError; }
    return renderer;
  }

  public Surface inputSurface() { return input; }
  public Surface outputSurface() { return output; }
  public void setFailureHandler(Runnable value) { failureHandler = value; }
  public void setMode(int value) { mode = value; }
  public void setSize(int width, int height) {
    if (width > 0 && height > 0) { this.width = width; this.height = height; }
  }

  private void initialize() {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] versions = new int[2];
    check(EGL14.eglInitialize(display, versions, 0, versions, 1), "eglInitialize");
    int[] attributes = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE};
    EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
    check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0, "eglChooseConfig");
    context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
        new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
    check(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
    window = EGL14.eglCreateWindowSurface(display, configs[0], output, new int[] {EGL14.EGL_NONE}, 0);
    check(window != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface");
    check(EGL14.eglMakeCurrent(display, window, window, context), "eglMakeCurrent");
    check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, dimensions, 0), "initial output width");
    check(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, dimensions, 1), "initial output height");
    float fit = Math.min((float) dimensions[0] / width, (float) dimensions[1] / height);
    if (!UpscalePolicy.upscale(mode, width, height, Math.round(width * fit), Math.round(height * fit), false))
      throw new NotNeeded();
    EGL14.eglSwapInterval(display, 0);
    String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
    check(extensions != null && extensions.contains("GL_OES_EGL_image_external"), "External video textures unsupported");
    int vertex = shader(GLES20.GL_VERTEX_SHADER, SgsrShader.VERTEX);
    int fragment = shader(GLES20.GL_FRAGMENT_SHADER, SgsrShader.FRAGMENT);
    program = GLES20.glCreateProgram();
    GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment);
    GLES20.glLinkProgram(program);
    int[] status = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
    GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
    check(status[0] != 0, "SGSR1 link: " + GLES20.glGetProgramInfoLog(program));
    position = GLES20.glGetAttribLocation(program, "position");
    transform = GLES20.glGetUniformLocation(program, "textureTransform");
    viewport = GLES20.glGetUniformLocation(program, "ViewportInfo[0]");
    enabled = GLES20.glGetUniformLocation(program, "upscalerEnabled");
    GLES20.glUseProgram(program);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "ps0"), 0);
    int[] names = new int[1]; GLES20.glGenTextures(1, names, 0); textureId = names[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    texture = new SurfaceTexture(textureId);
    texture.setDefaultBufferSize(width, height);
    input = new Surface(texture);
    texture.setOnFrameAvailableListener(ignored -> draw(), handler);
    check(GLES20.glGetError() == GLES20.GL_NO_ERROR, "SGSR1 initialization GL error");
  }

  private static int shader(int kind, String source) {
    int shader = GLES20.glCreateShader(kind); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
    int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
    if (status[0] == 0) { String log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new IllegalStateException(log); }
    return shader;
  }

  private void draw() {
    if (closed) return;
    try {
      long begin = System.nanoTime();
      texture.updateTexImage(); texture.getTransformMatrix(matrix);
      check(EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, dimensions, 0), "output width");
      check(EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, dimensions, 1), "output height");
      int sourceWidth = width, sourceHeight = height;
      float fit = Math.min((float) dimensions[0] / sourceWidth, (float) dimensions[1] / sourceHeight);
      int targetWidth = Math.max(1, Math.round(sourceWidth * fit));
      int targetHeight = Math.max(1, Math.round(sourceHeight * fit));
      boolean upscale = UpscalePolicy.upscale(mode, sourceWidth, sourceHeight, targetWidth, targetHeight, overloaded);
      GLES20.glClearColor(0, 0, 0, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
      GLES20.glViewport((dimensions[0] - targetWidth) / 2, (dimensions[1] - targetHeight) / 2, targetWidth, targetHeight);
      GLES20.glUseProgram(program);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
      GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0);
      GLES20.glUniform4f(viewport, 1f / sourceWidth, 1f / sourceHeight, sourceWidth, sourceHeight);
      GLES20.glUniform1f(enabled, upscale ? 1f : 0f);
      GLES20.glEnableVertexAttribArray(position);
      GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      check(GLES20.glGetError() == GLES20.GL_NO_ERROR, "SGSR1 draw error");
      // Native releaseOutputBufferAtTimestamp uses System.nanoTime. SurfaceTexture
      // retains that timestamp but does not itself schedule SurfaceView presentation.
      // Forward it to the final window so video keeps the player's audio clock.
      long timestamp = texture.getTimestamp(), now = System.nanoTime();
      EGLExt.eglPresentationTimeANDROID(display, window,
          timestamp > now - 1_000_000_000L && timestamp < now + 1_000_000_000L ? timestamp : now);
      check(EGL14.eglSwapBuffers(display, window), "eglSwapBuffers");
      long end = System.nanoTime();
      // A conservative queue-stall guard, not a claimed GPU benchmark. No glFinish/readback.
      if (lastFrame > 0 && end - begin > Math.max(8_000_000L, Math.min(20_000_000L, (begin - lastFrame) / 2))) stalls++;
      lastFrame = begin;
      if (++frames == 60) {
        if (stalls >= 10 && mode == 1) { overloaded = true; Log.i(TAG, "Auto: using bilinear after sustained renderer stalls"); }
        frames = 0; stalls = 0;
      }
    } catch (RuntimeException error) {
      Log.e(TAG, "Video surface unavailable", error);
      // A destroyed surface belongs to the player; release our resources, never the player's surface.
      closed = true; destroy(); thread.quitSafely();
      Runnable failure = failureHandler;
      if (failure != null) new Thread(failure, "TizenTube-video-fallback").start();
    }
  }

  public void close() {
    closed = true;
    if (android.os.Looper.myLooper() == thread.getLooper()) { destroy(); thread.quitSafely(); return; }
    CountDownLatch done = new CountDownLatch(1);
    if (!handler.post(() -> { destroy(); done.countDown(); thread.quitSafely(); })) return;
    try { if (!done.await(3, TimeUnit.SECONDS)) Log.w(TAG, "GPU teardown still pending"); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
  }

  private void destroy() {
    if (texture != null) texture.setOnFrameAvailableListener(null);
    if (input != null) { input.release(); input = null; }
    if (texture != null) { texture.release(); texture = null; }
    if (display != EGL14.EGL_NO_DISPLAY) {
      if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
      if (textureId != 0) { GLES20.glDeleteTextures(1, new int[] {textureId}, 0); textureId = 0; }
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
      if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
      EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
    }
    display = EGL14.EGL_NO_DISPLAY; window = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT;
  }

  private static void check(boolean valid, String operation) {
    if (!valid) throw new IllegalStateException(operation + " EGL=" + EGL14.eglGetError());
  }
}
