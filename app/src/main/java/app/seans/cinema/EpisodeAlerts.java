package app.seans.cinema;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/** Checks favourite series twice a day and notifies when a new episode or season appears. */
final class EpisodeAlerts {
    private static final String WORK = "episode-alerts";
    private static final String CHANNEL = "episodes";
    private EpisodeAlerts() {}

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("episode-alerts", 0); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }

    /** Favourites as the page keeps them; only series are watched. */
    static void syncFavorites(Context c, String json) { prefs(c).edit().putString("favorites", json).apply(); }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("enabled", on).apply();
        WorkManager work = WorkManager.getInstance(c);
        if (!on) { work.cancelUniqueWork(WORK); return; }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(Check.class, 12, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.MINUTES).build();
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    /** seasons × 1000 + episodes of the last season: grows whenever something new is published. */
    static int size(String url) throws Exception {
        JSONObject d = Catalog.details(url);
        JSONArray seasons = d.optJSONArray("seasons");
        if (seasons != null && seasons.length() > 0) {
            String last = seasons.getJSONObject(seasons.length() - 1).optString("url");
            JSONArray episodes = last.isEmpty() ? null : Catalog.details(last).optJSONArray("episodes");
            return seasons.length() * 1000 + (episodes == null ? 0 : episodes.length());
        }
        JSONArray episodes = d.optJSONArray("episodes");
        return episodes == null ? 0 : episodes.length();
    }

    public static final class Check extends Worker {
        public Check(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
        @NonNull @Override public Result doWork() {
            Context c = getApplicationContext();
            if (!enabled(c)) return Result.success();
            SharedPreferences p = prefs(c);
            JSONArray favorites;
            try { favorites = new JSONArray(p.getString("favorites", "[]")); } catch (Exception e) { return Result.success(); }
            SharedPreferences.Editor edit = p.edit();
            for (int i = 0; i < favorites.length(); i++) {
                JSONObject item = favorites.optJSONObject(i);
                if (item == null || !"Сериал".equals(item.optString("kind"))) continue;
                String url = item.optString("url");
                try {
                    int now = size(url), before = p.getInt("size:" + url, -1);
                    if (before >= 0 && now > before) announce(c, item, now / 1000 > before / 1000);
                    edit.putInt("size:" + url, now);
                } catch (Exception ignored) { } // A site that is down is checked again next time.
            }
            edit.apply();
            return Result.success();
        }
    }

    private static void announce(Context c, JSONObject item, boolean newSeason) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Новые серии", NotificationManager.IMPORTANCE_DEFAULT));
        String url = item.optString("url");
        Intent open = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .putExtra("page", url).putExtra("item", item.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(c, url.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder n = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.seans).setContentTitle(item.optString("title"))
                .setContentText(newSeason ? "Вышел новый сезон" : "Вышла новая серия")
                .setContentIntent(tap).setAutoCancel(true);
        try { NotificationManagerCompat.from(c).notify(url.hashCode(), n.build()); }
        catch (SecurityException ignored) { } // Notifications were not allowed.
    }
}
