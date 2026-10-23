package dev.cobalt.upscalertest;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import dev.cobalt.updates.VideoUpscaler;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises actual MediaCodec -> external-OES -> SGSR1 -> Android Surface output. */
public class SmokeActivity extends Activity implements SurfaceHolder.Callback {
  private boolean started;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    SurfaceView view = new SurfaceView(this);
    setContentView(view); view.getHolder().setFixedSize(640, 360); view.getHolder().addCallback(this);
  }
  public void surfaceCreated(SurfaceHolder holder) {}
  public void surfaceDestroyed(SurfaceHolder holder) {}
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    if (started) return; started = true;
    dev.cobalt.media.VideoSurfaceView.current = holder.getSurface();
    new Thread(() -> {
      try {
        runDecoder(holder.getSurface(), 0);
        runDecoder(holder.getSurface(), 1);
        result("PASS: decoded SDR, orientation, color, SGSR1/bilinear difference, adaptive size, teardown/recreate");
      } catch (Throwable error) {
        android.util.Log.e("SGSR1_TEST", "FAIL", error);
        result("FAIL: " + error.toString());
      }
    }, "upscaler-smoke-test").start();
  }

  private void runDecoder(Surface output, int cycle) throws Exception {
    VideoUpscaler.command(this, "tizentube://upscaler/auto");
    MediaExtractor extractor = new MediaExtractor();
    android.content.res.AssetFileDescriptor file = getAssets().openFd("fixture.mp4");
    extractor.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength()); file.close();
    extractor.selectTrack(0); MediaFormat format = extractor.getTrackFormat(0);
    MediaCodec codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
    try {
      VideoUpscaler.configure(codec, format, output, null, 0);
      java.lang.reflect.Field active = VideoUpscaler.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
      if (((java.util.Map) active.get(null)).size() != 1) throw new AssertionError("Decoder output did not connect to SGSR1");
      codec.start();
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      int frames = 0; boolean end = false; Bitmap enhanced = null, bilinear = null;
      long deadline = System.nanoTime() + 20_000_000_000L;
      while (frames < 26 && System.nanoTime() < deadline) {
        if (!end) {
          int index = codec.dequeueInputBuffer(10000);
          if (index >= 0) {
            ByteBuffer buffer = codec.getInputBuffer(index);
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); end = true; }
            else { codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0); extractor.advance(); }
          }
        }
        int index = codec.dequeueOutputBuffer(info, 10000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) VideoUpscaler.outputFormat(codec, codec.getOutputFormat());
        if (index >= 0) {
          codec.releaseOutputBuffer(index, System.nanoTime() + 20_000_000L); frames++; Thread.sleep(40);
          if (frames == 8) { enhanced = capture(output); verifyCorners(enhanced); VideoUpscaler.command(this, "tizentube://upscaler/off"); }
          if (frames == 16) { bilinear = capture(output); verifyCorners(bilinear); VideoUpscaler.command(this, "tizentube://upscaler/quality"); }
          if (frames == 24) {
            Bitmap quality = capture(output); verifyCorners(quality);
            int changed = 0, restored = 0;
            for (int y = 100; y < 260; y++) for (int x = 180; x < 460; x++) {
              if (enhanced.getPixel(x,y) != bilinear.getPixel(x,y)) changed++;
              if (quality.getPixel(x,y) == enhanced.getPixel(x,y)) restored++;
            }
            if (changed < 100) throw new AssertionError("SGSR1 output matches bilinear: " + changed);
            if (restored < 40000) throw new AssertionError("Quality did not restore SGSR1 output: " + restored);
            try (FileOutputStream screenshot = new FileOutputStream(new File(getFilesDir(), "sgsr1-" + cycle + ".png"))) {
              quality.compress(Bitmap.CompressFormat.PNG, 100, screenshot);
            }
            // Test an adaptive format/crop update without reallocating full-size textures.
            VideoUpscaler.outputFormat(codec, format);
          }
        }
      }
      if (frames < 26) throw new AssertionError("Decoder did not deliver frames: " + frames);
      codec.stop();
    } finally { VideoUpscaler.release(codec); extractor.release(); }
  }

  private Bitmap capture(Surface output) throws Exception {
    Thread.sleep(100);
    Bitmap bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
    CountDownLatch ready = new CountDownLatch(1); int[] status = {-1};
    PixelCopy.request(output, bitmap, value -> { status[0] = value; ready.countDown(); }, new Handler(Looper.getMainLooper()));
    if (!ready.await(3, TimeUnit.SECONDS) || status[0] != PixelCopy.SUCCESS) throw new AssertionError("PixelCopy: " + status[0]);
    return bitmap;
  }

  private static void verifyCorners(Bitmap bitmap) {
    int red = bitmap.getPixel(40, 40), green = bitmap.getPixel(600, 40), blue = bitmap.getPixel(40, 320);
    if (android.graphics.Color.red(red) < 180 || android.graphics.Color.green(red) > 70
        || android.graphics.Color.green(green) < 150 || android.graphics.Color.red(green) > 70
        || android.graphics.Color.blue(blue) < 180 || android.graphics.Color.red(blue) > 70)
      throw new AssertionError("Orientation/color mismatch " + Integer.toHexString(red) + "/" + Integer.toHexString(green) + "/" + Integer.toHexString(blue));
  }

  private void result(String message) {
    try (FileOutputStream file = openFileOutput("result.txt", 0)) { file.write(message.getBytes("UTF-8")); }
    catch (Exception error) { android.util.Log.e("SGSR1_TEST", "Could not save result", error); }
    android.util.Log.i("SGSR1_TEST", message);
  }
}
