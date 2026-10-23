package dev.cobalt.media;
import android.view.Surface;
/** Test fixture for Cobalt's public display-surface lookup. Never included in release APKs. */
public class VideoSurfaceView {
  public static Surface current;
  public static Surface getCurrentSurface() { return current; }
}
