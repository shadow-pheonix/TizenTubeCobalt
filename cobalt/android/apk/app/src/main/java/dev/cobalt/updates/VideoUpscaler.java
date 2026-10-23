package dev.cobalt.updates;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;
import java.util.IdentityHashMap;
import java.util.Map;

/** Hooks only Cobalt's video configure/release and output-format callback. */
public final class VideoUpscaler {
  private static final String TAG = "TizenTubeUpscaler";
  private static final Map<MediaCodec, UpscaleRenderer> ACTIVE = new IdentityHashMap<>();
  private static volatile int mode;
  private static volatile Context context;

  private VideoUpscaler() {}

  public static void initialize(Context supplied) {
    if (context == null) {
      context = supplied.getApplicationContext();
      mode = UpscalePolicy.mode(context.getSharedPreferences("fork-video", 0).getString("upscaler", "auto"));
    }
  }

  /** Local control through the existing JNI string bridge; never treated as an APK URL. */
  public static boolean command(Context supplied, String command) {
    if (command == null || !command.startsWith("tizentube://upscaler/")) return false;
    initialize(supplied);
    String selected = command.substring("tizentube://upscaler/".length());
    if (!selected.equals("auto") && !selected.equals("quality") && !selected.equals("off")) return true;
    mode = UpscalePolicy.mode(selected);
    context.getSharedPreferences("fork-video", 0).edit().putString("upscaler", selected).apply();
    synchronized (ACTIVE) { for (UpscaleRenderer renderer : ACTIVE.values()) renderer.setMode(mode); }
    return true;
  }

  private static int integer(MediaFormat format, String key, int fallback) {
    try { return format.containsKey(key) ? format.getInteger(key) : fallback; }
    catch (RuntimeException ignored) { return fallback; }
  }

  private static boolean hdr(MediaFormat format) {
    int transfer = integer(format, "color-transfer", 0);
    return transfer == 6 || transfer == 7 || integer(format, "color-standard", 0) == 6
        || format.containsKey("hdr-static-info") || format.containsKey("hdr10-plus-info");
  }

  private static boolean displaySurface(Surface surface) {
    // Decode-to-texture and previews must retain their native compositor path.
    try {
      return surface != null && surface == Class.forName("dev.cobalt.media.VideoSurfaceView")
          .getMethod("getCurrentSurface").invoke(null);
    } catch (Exception ignored) { return false; }
  }

  public static void configure(MediaCodec codec, MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
    boolean tunneled = false;
    try { tunneled = format.getFeatureEnabled("tunneled-playback"); } catch (RuntimeException ignored) {}
    int width = integer(format, "width", 0), height = integer(format, "height", 0);
    boolean secure = crypto != null || (flags & MediaCodec.CONFIGURE_FLAG_ENCODE) != 0;
    try { secure |= codec.getName().endsWith(".secure"); } catch (RuntimeException ignored) { secure = true; }
    UpscaleRenderer renderer = null;
    if (context != null && UpscalePolicy.eligible(mode, width, height, secure, hdr(format), tunneled, displaySurface(surface))) {
      try { renderer = UpscaleRenderer.create(surface, width, height, mode); }
      catch (UpscaleRenderer.NotNeeded ignored) { /* Native/downscale: keep the hardware path. */ }
      catch (Exception error) { Log.w(TAG, "GPU initialization failed; keeping direct video output", error); }
    }
    if (renderer == null) { codec.configure(format, surface, crypto, flags); return; }
    try {
      codec.configure(format, renderer.inputSurface(), crypto, flags);
      synchronized (ACTIVE) { ACTIVE.put(codec, renderer); }
      final UpscaleRenderer connected = renderer;
      renderer.setFailureHandler(() -> {
        synchronized (ACTIVE) {
          if (ACTIVE.get(codec) != connected) return;
          ACTIVE.remove(codec);
        }
        if (surface.isValid()) {
          try { codec.setOutputSurface(surface); }
          catch (RuntimeException error) { Log.w(TAG, "Player will recreate the failed output surface", error); }
        }
      });
      Log.i(TAG, "SGSR1 decoder surface connected");
    } catch (RuntimeException error) {
      renderer.close();
      codec.configure(format, surface, crypto, flags);
    }
  }

  public static void outputFormat(MediaCodec codec, MediaFormat format) {
    UpscaleRenderer renderer;
    synchronized (ACTIVE) { renderer = ACTIVE.get(codec); }
    if (renderer == null) return;
    if (hdr(format)) {
      synchronized (ACTIVE) { ACTIVE.remove(codec); }
      Surface output = renderer.outputSurface();
      renderer.close();
      try { codec.setOutputSurface(output); }
      catch (RuntimeException error) { Log.e(TAG, "Unable to restore HDR output surface", error); }
      return;
    }
    int width = integer(format, "width", 0), height = integer(format, "height", 0);
    if (format.containsKey("crop-right") && format.containsKey("crop-left"))
      width = integer(format, "crop-right", width - 1) - integer(format, "crop-left", 0) + 1;
    if (format.containsKey("crop-bottom") && format.containsKey("crop-top"))
      height = integer(format, "crop-bottom", height - 1) - integer(format, "crop-top", 0) + 1;
    renderer.setSize(width, height);
  }

  public static void release(MediaCodec codec) {
    UpscaleRenderer renderer;
    synchronized (ACTIVE) { renderer = ACTIVE.remove(codec); }
    try { codec.release(); } finally { if (renderer != null) renderer.close(); }
  }
}
