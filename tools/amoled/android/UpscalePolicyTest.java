import dev.cobalt.updates.UpscalePolicy;

public class UpscalePolicyTest {
  private static int count;
  private static void check(boolean value) { count++; if (!value) throw new AssertionError("Upscaler check " + count); }
  public static void main(String[] args) {
    check(UpscalePolicy.mode("auto") == 1);
    check(UpscalePolicy.mode("quality") == 2);
    check(UpscalePolicy.mode("off") == 0);
    check(UpscalePolicy.mode("untrusted") == 0);
    check(UpscalePolicy.eligible(1, 1920, 1080, false, false, false, true));
    check(!UpscalePolicy.eligible(0, 1920, 1080, false, false, false, true));
    check(!UpscalePolicy.eligible(1, 1920, 1080, true, false, false, true));
    check(!UpscalePolicy.eligible(1, 1920, 1080, false, true, false, true));
    check(!UpscalePolicy.eligible(1, 1920, 1080, false, false, true, true));
    check(!UpscalePolicy.eligible(1, 1920, 1080, false, false, false, false));
    check(!UpscalePolicy.eligible(1, 0, 0, false, false, false, true));
    check(UpscalePolicy.upscale(1, 1920, 1080, 3840, 2160, false));
    check(!UpscalePolicy.upscale(1, 3840, 2160, 3840, 2160, false));
    check(!UpscalePolicy.upscale(1, 3840, 2160, 1920, 1080, false));
    check(!UpscalePolicy.upscale(1, 1920, 1080, 3840, 2160, true));
    check(UpscalePolicy.upscale(2, 1920, 1080, 3840, 2160, true));
    check(!UpscalePolicy.upscale(0, 1920, 1080, 3840, 2160, false));
    check(!UpscalePolicy.upscale(1, 0, 0, 3840, 2160, false));
    check(!UpscalePolicy.upscale(1, 1920, 1080, 1921, 1081, false));
    check(!UpscalePolicy.upscale(1, 1080, 1920, 1920, 1080, false));
    System.out.println(count + " upscaler policy checks passed");
  }
}
