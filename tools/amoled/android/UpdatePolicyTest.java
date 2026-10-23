import dev.cobalt.updates.UpdatePolicy;

public final class UpdatePolicyTest {
  interface Action { void run() throws Exception; }
  static int tests;
  static void check(boolean value) { tests++; if (!value) throw new AssertionError("Policy check " + tests + " failed"); }
  static void rejects(Action action) throws Exception { tests++; try { action.run(); } catch (Exception expected) { return; } throw new AssertionError("Expected rejection"); }
  public static void main(String[] args) throws Exception {
    check(UpdatePolicy.compare("v2.0.3-amoled.10", "v2.0.3-amoled.9") > 0);
    check(UpdatePolicy.compare("v2.0.3-amoled.4", "2.0.3") > 0);
    check(UpdatePolicy.compare("v2.0.3-amoled.4", "2.0.3-amoled.4") == 0);
    check(UpdatePolicy.compare("v2.0.2-amoled.99", "v2.0.3-amoled.1") < 0);
    check(UpdatePolicy.compare("v2.1.0-amoled.1", "v2.0.3-amoled.100") > 0);
    String url = "https://github.com/shadow-pheonix/TizenTubeCobalt/releases/download/v2.0.3-amoled.4/TizenTube-AMOLED-arm64.apk";
    String suffix = "#version=v2.0.3-amoled.4&sha256=" + new String(new char[64]).replace('\0', 'a') + "&size=123&manual=1";
    UpdatePolicy.Request request = UpdatePolicy.parse(url + suffix, "arm64-v8a");
    check(request.manual && request.size == 123 && request.url.equals(url));
    rejects(() -> UpdatePolicy.parse(url + suffix, "armeabi-v7a"));
    rejects(() -> UpdatePolicy.parse(url.replace("https:", "http:") + suffix, "arm64-v8a"));
    rejects(() -> UpdatePolicy.parse(url.replace("github.com", "github.com.evil.invalid") + suffix, "arm64-v8a"));
    rejects(() -> UpdatePolicy.parse(url.replace("shadow-pheonix", "someone-else") + suffix, "arm64-v8a"));
    rejects(() -> UpdatePolicy.parse(url + suffix.replace("size=123", "size=-1"), "arm64-v8a"));
    rejects(() -> UpdatePolicy.parse(url + suffix.replace("size=123", "size=9999999999"), "arm64-v8a"));
    rejects(() -> UpdatePolicy.parse(url + suffix.replace("sha256=", "other="), "arm64-v8a"));
    rejects(() -> UpdatePolicy.parse(url + suffix + "&size=1", "arm64-v8a"));
    rejects(() -> UpdatePolicy.parse(url + suffix.replace("version=v2.0.3-amoled.4", "version=v2.0.3-amoled.5"), "arm64-v8a"));
    rejects(() -> UpdatePolicy.checkRedirect(new java.net.URI("http://release-assets.githubusercontent.com/file")));
    rejects(() -> UpdatePolicy.checkRedirect(new java.net.URI("https://other.invalid/file")));
    UpdatePolicy.checkRedirect(new java.net.URI("https://release-assets.githubusercontent.com/file")); tests++;
    System.out.println("Passed " + tests + " native updater policy checks.");
  }
}
