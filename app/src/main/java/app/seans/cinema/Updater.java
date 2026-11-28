package app.seans.cinema;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Checks the GitHub releases of the app and installs a newer APK through the system installer. */
final class Updater {
    static final String REPO = "leojakee/seans";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String TORRSERVE_APK = "https://github.com/YouROK/TorrServe/releases/download/"
            + "MatriX.145.Client/TorrServe_MatriX.145.Client.apk";

    /** {current, latest, newer, url, notes, page} or {error}. */
    static JSONObject check(Activity activity) throws Exception {
        String current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        HttpURLConnection c = open(API);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) throw new Exception("Выпусков пока нет, или репозиторий закрыт для приложения (приватный).");
        if (code != 200) throw new Exception("GitHub ответил HTTP " + code + ".");
        JSONObject release;
        try (InputStream in = c.getInputStream()) { release = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
        String latest = release.optString("tag_name").replaceFirst("^[vV]", "");
        String apk = "";
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break; }
        }
        return new JSONObject().put("current", current).put("latest", latest)
                .put("newer", compare(latest, current) > 0 && !apk.isEmpty())
                .put("url", apk).put("notes", release.optString("body")).put("page", release.optString("html_url"));
    }

    /** Downloads the APK into the app cache and hands it to the system installer. */
    static void install(Activity activity, String url) throws Exception {
        if (!url.startsWith("https://github.com/" + REPO + "/releases/download/"))
            throw new Exception("Ссылка на обновление не из репозитория приложения.");
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.runOnUiThread(() -> activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()))));
            throw new Exception("Разрешите «Сеансу» устанавливать приложения и нажмите «Обновить» ещё раз.");
        }
        File file = downloadApk(activity, url, "seans-update.apk");
        activity.runOnUiThread(() -> openInstaller(activity, file));
    }

    static File downloadTorrServe(Activity activity) throws Exception {
        return downloadApk(activity, TORRSERVE_APK, "torrserve-matrix.apk");
    }

    private static File downloadApk(Activity activity, String url, String filename) throws Exception {
        File dir = new File(activity.getCacheDir(), "updates");
        dir.mkdirs();
        File file = new File(dir, filename);
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Не удалось скачать APK: HTTP " + code + ".");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(file)) { in.transferTo(out); }
            return file;
        } finally {
            c.disconnect();
        }
    }

    static void openInstaller(Activity activity, File file) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", file);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(intent);
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Seans-Android-Updater");
        return c;
    }

    /** Compares dotted versions: 1.10 > 1.9 > 1.8.1 > 1.8. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }
}
