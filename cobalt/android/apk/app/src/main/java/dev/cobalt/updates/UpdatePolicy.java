package dev.cobalt.updates;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure validation shared by the downloader and its JVM tests. */
public final class UpdatePolicy {
  public static final String REPOSITORY = "shadow-pheonix/TizenTubeCobalt";
  private static final Pattern VERSION = Pattern.compile("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:-amoled\\.([0-9]+))?$");
  public static final long MAX_APK_SIZE = 1024L * 1024 * 1024;

  public static long[] version(String value) {
    Matcher match = VERSION.matcher(value == null ? "" : value);
    if (!match.matches()) throw new IllegalArgumentException("Invalid update version");
    long[] parts = new long[4];
    for (int i = 0; i < 4; i++) parts[i] = match.group(i + 1) == null ? 0 : Long.parseLong(match.group(i + 1));
    return parts;
  }

  public static int compare(String first, String second) {
    long[] a = version(first), b = version(second);
    for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return Long.compare(a[i], b[i]);
    return 0;
  }

  public static final class Request {
    public final String url, sha256, version, architecture;
    public final long size;
    public final boolean manual;

    private Request(String url, String sha256, String version, String architecture, long size, boolean manual) {
      this.url = url; this.sha256 = sha256; this.version = version;
      this.architecture = architecture; this.size = size; this.manual = manual;
    }
  }

  public static Request parse(String command, String architecture) throws Exception {
    URI uri = new URI(command);
    if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null) {
      throw new IllegalArgumentException("Updates must come from this fork's GitHub releases");
    }
    String arch;
    if ("arm64-v8a".equals(architecture)) arch = "arm64";
    else if ("armeabi-v7a".equals(architecture)) arch = "arm";
    else throw new IllegalArgumentException("Unsupported APK architecture");
    Map<String, String> args = new HashMap<>();
    if (uri.getRawFragment() != null) for (String part : uri.getRawFragment().split("&")) {
      String[] pair = part.split("=", 2);
      if (pair.length != 2 || args.put(pair[0], URLDecoder.decode(pair[1], "UTF-8")) != null)
        throw new IllegalArgumentException("Invalid update metadata");
    }
    String version = args.get("version"), digest = args.get("sha256");
    version(version);
    if (version == null || !version.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+-amoled\\.[0-9]+"))
      throw new IllegalArgumentException("Not a fork release");
    if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Missing APK checksum");
    long size = Long.parseLong(args.get("size"));
    if (size <= 0 || size > MAX_APK_SIZE) throw new IllegalArgumentException("Invalid APK size");
    String path = "/" + REPOSITORY + "/releases/download/" + version + "/TizenTube-AMOLED-" + arch + ".apk";
    if (!path.equals(uri.getRawPath())) throw new IllegalArgumentException("Incorrect update asset");
    return new Request("https://github.com" + path, digest, version, architecture, size, "1".equals(args.get("manual")));
  }

  public static void checkRedirect(URI uri) {
    String host = uri.getHost();
    if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
        || !("github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
             || "objects.githubusercontent.com".equals(host)))
      throw new IllegalArgumentException("Unexpected update download redirect");
  }

  private UpdatePolicy() {}
}
