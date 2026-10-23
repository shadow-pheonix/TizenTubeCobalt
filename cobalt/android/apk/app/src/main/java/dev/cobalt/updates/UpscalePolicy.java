package dev.cobalt.updates;

/** Device-independent decisions; no network, neural model, or CPU frame copies. */
public final class UpscalePolicy {
  private UpscalePolicy() {}
  public static int mode(String value) {
    if ("auto".equals(value)) return 1;
    if ("quality".equals(value)) return 2;
    return 0;
  }

  public static boolean eligible(int mode, int width, int height, boolean protectedVideo,
      boolean hdr, boolean tunneled, boolean displaySurface) {
    return mode > 0 && width > 0 && height > 0 && !protectedVideo && !hdr
        && !tunneled && displaySurface;
  }

  public static boolean upscale(int mode, int sourceWidth, int sourceHeight,
      int outputWidth, int outputHeight, boolean overloaded) {
    if (mode == 0 || sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return false;
    // Require a meaningful increase on both axes. Never sharpen native video or downscales.
    if (outputWidth < sourceWidth * 1.05 || outputHeight < sourceHeight * 1.05) return false;
    return mode == 2 || !overloaded;
  }
}
