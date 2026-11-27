package app.seans.cinema;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;
import org.json.JSONObject;

/** Keeps the Android TV home-screen row "Play next" in step with unfinished titles. */
final class WatchNext {
    private WatchNext() {}

    static void update(Context context, JSONObject item, String page, String title, long position, long duration, boolean finished) {
        if (!context.getPackageManager().hasSystemFeature("android.software.leanback")) return;
        Context app = context.getApplicationContext();
        String itemJson = item.toString();
        new Thread(() -> {
            try { write(app, new JSONObject(itemJson), page, title, position, duration, finished); }
            catch (Exception ignored) { } // The launcher may not offer a Play next row; the app works without it.
        }).start();
    }

    private static synchronized void write(Context context, JSONObject item, String page, String title, long position, long duration, boolean finished) {
        SharedPreferences ids = context.getSharedPreferences("watch-next", 0);
        long id = ids.getLong(page, -1);
        if (finished || position <= 0) {
            if (id >= 0) {
                context.getContentResolver().delete(TvContractCompat.buildWatchNextProgramUri(id), null, null);
                ids.edit().remove(page).apply();
            }
            return;
        }
        Intent open = new Intent(context, MainActivity.class).setAction(Intent.ACTION_VIEW)
                .putExtra("page", page).putExtra("item", item.toString());
        boolean series = "Сериал".equals(item.optString("kind"));
        WatchNextProgram.Builder program = new WatchNextProgram.Builder()
                .setType(series ? TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE : TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setTitle(item.optString("title", title))
                .setDescription(title)
                .setLastPlaybackPositionMillis((int) Math.min(Integer.MAX_VALUE, position))
                .setDurationMillis((int) Math.min(Integer.MAX_VALUE, Math.max(duration, position)))
                .setInternalProviderId(page)
                .setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)));
        String poster = item.optString("poster");
        if (poster.startsWith("https://")) program.setPosterArtUri(Uri.parse(poster)).setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3);
        ContentValues values = program.build().toContentValues();
        if (id >= 0 && context.getContentResolver().update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null) > 0) return;
        Uri created = context.getContentResolver().insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values);
        if (created != null) ids.edit().putLong(page, android.content.ContentUris.parseId(created)).apply();
    }
}
