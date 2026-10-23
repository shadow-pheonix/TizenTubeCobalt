package dev.cobalt.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/** Downloads fork updates; installation always requires user confirmation. */
public final class ForkUpdater {
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();
  private static WeakReference<Activity> activity = new WeakReference<>(null);
  private static boolean callbacksRegistered, foreground;
  private static volatile boolean dialogVisible, awaitingPermission;
  private static UpdatePolicy.Request ready;
  private static File readyFile;
  private static volatile JSONObject installed;

  private static JSONObject readJson(InputStream input) throws Exception {
    try (InputStream in = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096]; int count;
      while ((count = in.read(buffer)) != -1) {
        if (bytes.size() + count > 16384) throw new IllegalArgumentException("Update metadata is too large");
        bytes.write(buffer, 0, count);
      }
      return new JSONObject(bytes.toString("UTF-8"));
    }
  }

  private static JSONObject metadata(Context context) throws Exception {
    if (installed == null) installed = readJson(context.getAssets().open("tizentube-fork.json"));
    return installed;
  }

  public static String getVersion(Context context) {
    try { return metadata(context).getString("version"); }
    catch (Exception ignored) {
      try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
      catch (Exception failure) { return "unknown"; }
    }
  }

  public static String getArchitecture(Context context) {
    try { return metadata(context).getString("architecture"); }
    catch (Exception ignored) { return android.os.Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a"; }
  }

  public static boolean start(Context suppliedContext, Activity suppliedActivity, String command) {
    if (dialogVisible || awaitingPermission) return false;
    final Context context = suppliedContext.getApplicationContext();
    final UpdatePolicy.Request request;
    try {
      request = UpdatePolicy.parse(command, getArchitecture(context));
      if (UpdatePolicy.compare(request.version, getVersion(context)) <= 0) return false;
      if (!request.manual && context.getSharedPreferences("fork-updater", 0).getLong("postponedUntil", 0) > System.currentTimeMillis()) return false;
    } catch (Exception error) {
      MAIN.post(() -> failure(context, "The update information could not be verified."));
      return false;
    }
    MAIN.post(() -> {
      if (suppliedActivity != null && !suppliedActivity.isFinishing()) { activity = new WeakReference<>(suppliedActivity); foreground = true; }
      registerCallbacks(context);
    });
    if (!DOWNLOADING.compareAndSet(false, true)) return false;
    MAIN.post(() -> Toast.makeText(context, "Downloading TizenTube update " + request.version, Toast.LENGTH_LONG).show());
    java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    worker.execute(() -> {
      File file = new File(context.getFilesDir(), "tizentube-update-" + request.sha256 + ".apk");
      File partial = new File(file.getPath() + ".part");
      try {
        if (!file.isFile() || !request.sha256.equals(hash(file))) {
          download(request, partial);
          // Android package parsers expect an .apk path. The downloader has
          // already checked the complete size and hash; verify package/signers
          // below before making the file available to the install prompt.
          if (file.exists() && !file.delete()) throw new IllegalStateException("Could not replace the cached update");
          if (!partial.renameTo(file)) throw new IllegalStateException("Could not save the downloaded update");
        }
        verifyApk(context, file, request);
        File[] older = context.getFilesDir().listFiles();
        if (older != null) for (File candidate : older) {
          if (!candidate.equals(file) && candidate.getName().matches("tizentube-update-[a-f0-9]{64}\\.apk(?:\\.part)?")) candidate.delete();
        }
        MAIN.post(() -> { DOWNLOADING.set(false); ready = request; readyFile = file; prompt(context); });
      } catch (Exception error) {
        partial.delete();
        file.delete();
        MAIN.post(() -> { DOWNLOADING.set(false); failure(context, "The update could not be downloaded or verified. Check your connection and try Check for updates again."); });
      } finally { worker.shutdown(); }
    });
    return true;
  }

  private static void download(UpdatePolicy.Request request, File partial) throws Exception {
    URI destination = new URI(request.url);
    HttpURLConnection connection = null;
    try {
      for (int redirect = 0; redirect <= 5; redirect++) {
        UpdatePolicy.checkRedirect(destination);
        connection = (HttpURLConnection) destination.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
        connection.setRequestProperty("User-Agent", "TizenTube-Fork-Updater");
        int status = connection.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
          String location = connection.getHeaderField("Location");
          if (location == null) throw new IllegalStateException("Missing download redirect");
          destination = destination.resolve(location); connection.disconnect(); connection = null; continue;
        }
        if (status != 200) throw new IllegalStateException("Download failed: " + status);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
          byte[] buffer = new byte[65536]; int count;
          while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > request.size) throw new IllegalStateException("Unexpected APK size");
            digest.update(buffer, 0, count); out.write(buffer, 0, count);
          }
          out.getFD().sync();
        }
        if (total != request.size || !request.sha256.equals(hex(digest.digest()))) throw new IllegalStateException("APK checksum mismatch");
        return;
      }
      throw new IllegalStateException("Too many download redirects");
    } finally { if (connection != null) connection.disconnect(); }
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }

  private static String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[65536]; int count;
      while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    return hex(digest.digest());
  }

  private static Set<String> signers(PackageInfo info) throws Exception {
    Signature[] certificates = Build.VERSION.SDK_INT >= 28
        ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
    Set<String> result = new HashSet<>();
    if (certificates != null) for (Signature certificate : certificates) result.add(hex(MessageDigest.getInstance("SHA-256").digest(certificate.toByteArray())));
    if (result.isEmpty()) throw new IllegalStateException("Missing signing certificate");
    return result;
  }

  private static void verifyApk(Context context, File file, UpdatePolicy.Request request) throws Exception {
    if (file.length() != request.size || !request.sha256.equals(hash(file))) throw new IllegalStateException("APK checksum mismatch");
    PackageManager manager = context.getPackageManager();
    int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo current = manager.getPackageInfo(context.getPackageName(), flags);
    PackageInfo next = manager.getPackageArchiveInfo(file.getPath(), flags);
    if (next == null || !context.getPackageName().equals(next.packageName) || !signers(current).equals(signers(next)))
      throw new IllegalStateException("Incorrect update package or signing certificate");
    long currentCode = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
    long nextCode = Build.VERSION.SDK_INT >= 28 ? next.getLongVersionCode() : next.versionCode;
    if (nextCode < currentCode) throw new IllegalStateException("Refusing to downgrade the installed app");
    try (ZipFile zip = new ZipFile(file)) {
      JSONObject stamp = readJson(zip.getInputStream(zip.getEntry("assets/tizentube-fork.json")));
      if (!request.version.equals(stamp.getString("version")) || !request.architecture.equals(stamp.getString("architecture"))
          || !UpdatePolicy.REPOSITORY.equals(stamp.getString("repository"))) throw new IllegalStateException("Incorrect fork release metadata");
    }
  }

  private static void prompt(Context context) {
    Activity current = activity.get();
    if (ready == null || dialogVisible || !foreground || current == null || current.isFinishing()) return;
    dialogVisible = true;
    new AlertDialog.Builder(current).setTitle("Update ready to install")
        .setMessage("TizenTube " + ready.version + " has been downloaded and verified. Install the update now?")
        .setPositiveButton("Install", (dialog, which) -> { dialogVisible = false; openInstaller(context); })
        .setNegativeButton("Later", (dialog, which) -> postpone(context))
        .setOnCancelListener(dialog -> postpone(context)).show();
  }

  private static void postpone(Context context) {
    context.getSharedPreferences("fork-updater", 0).edit().putLong("postponedUntil", System.currentTimeMillis() + 24 * 60 * 60 * 1000L).apply();
    dialogVisible = false; ready = null;
  }

  private static void openInstaller(Context context) {
    Activity current = activity.get();
    if (current == null || current.isFinishing() || ready == null || readyFile == null) return;
    if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
      awaitingPermission = true;
      try {
        current.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName())));
      } catch (Exception error) { awaitingPermission = false; failure(context, "Allow installs from TizenTube in Android settings, then try Check for updates again. The downloaded update is saved."); }
      return;
    }
    try {
      // Use the app's existing FileProvider, whose exact method is also checked
      // by the build-time bytecode patcher.
      Class<?> provider = Class.forName("androidx.core.content.FileProvider");
      Method method = provider.getMethod("getUriForFile", Context.class, String.class, File.class);
      Uri uri = (Uri) method.invoke(null, context, context.getPackageName() + ".fileprovider", readyFile);
      Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      current.startActivity(intent);
      ready = null; awaitingPermission = false;
    } catch (Exception error) { failure(context, "Android's package installer could not be opened. The downloaded update is saved; try Check for updates again."); }
  }

  private static void failure(Context context, String message) {
    Activity current = activity.get();
    if (foreground && current != null && !current.isFinishing()) new AlertDialog.Builder(current).setTitle("Update unavailable").setMessage(message).setPositiveButton("OK", null).show();
    else Toast.makeText(context, message, Toast.LENGTH_LONG).show();
  }

  private static void registerCallbacks(Context context) {
    if (callbacksRegistered) return;
    callbacksRegistered = true;
    ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
      public void onActivityResumed(Activity resumed) {
        activity = new WeakReference<>(resumed); foreground = true;
        if (awaitingPermission) {
          awaitingPermission = false;
          if (Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls()) openInstaller(context);
          else { ready = null; Toast.makeText(context, "Installation permission was not granted. The update is saved; use Check for updates to try again.", Toast.LENGTH_LONG).show(); }
        } else prompt(context);
      }
      public void onActivityPaused(Activity paused) { if (activity.get() == paused) foreground = false; }
      public void onActivityCreated(Activity value, Bundle state) {}
      public void onActivityStarted(Activity value) {}
      public void onActivityStopped(Activity value) {}
      public void onActivitySaveInstanceState(Activity value, Bundle state) {}
      public void onActivityDestroyed(Activity value) { if (activity.get() == value) { foreground = false; dialogVisible = false; } }
    });
  }

  private ForkUpdater() {}
}
