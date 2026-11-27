package app.seans.cinema;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.view.WindowInsetsController;
import android.view.WindowInsets;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE = "https://appassets.androidplatform.net/";
    private static final Set<String> FILES = new java.util.HashSet<>(java.util.Arrays.asList("index.html","screen.css","material.js","sources.js","playback.js","search.js","feed.js","cinema.js"));
    private final ExecutorService network = Executors.newFixedThreadPool(8);
    private final ExecutorService details = Executors.newFixedThreadPool(2);
    private WebView web;
    private boolean tvMode;
    private volatile String pendingLaunch;
    private void readLaunch(Intent intent) {
        if (intent == null || intent.getStringExtra("page") == null || intent.getStringExtra("item") == null) return;
        try {
            pendingLaunch = new JSONObject().put("page", intent.getStringExtra("page"))
                    .put("item", new JSONObject(intent.getStringExtra("item"))).toString();
        } catch (Exception ignored) { pendingLaunch = null; }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readLaunch(intent);
        if (web != null && pendingLaunch != null) web.evaluateJavascript("window.openLaunch && openLaunch()", null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        readLaunch(getIntent());
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(18,20,19));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setTextZoom(100);
        String styleNonce=java.util.UUID.randomUUID().toString();
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String uri = r.getUrl().toString();
                if (uri.startsWith(BASE)) {
                    String name = uri.substring(BASE.length());
                    if (FILES.contains(name)) try {
                        if("index.html".equals(name)){
                            try(java.util.Scanner reader=new java.util.Scanner(getAssets().open(name),"UTF-8")){
                                String html=reader.useDelimiter("\\A").next().replace("__STYLE_NONCE__",styleNonce);
                                return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(
                                        html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                            }
                        }
                        String type = name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : "text/html";
                        return new WebResourceResponse(type, "UTF-8", getAssets().open(name));
                    } catch (IOException ignored) { }
                } else if (!r.isForMainFrame() && "https".equals(r.getUrl().getScheme())
                        && r.getRequestHeaders().getOrDefault("Accept","").contains("image/")) return null;
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        web.addJavascriptInterface(new Bridge(),"Cinema");
        FrameLayout surface = new FrameLayout(this);
        surface.setBackgroundColor(Color.rgb(18,20,19));
        surface.addView(web, new FrameLayout.LayoutParams(-1,-1));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            // WebView lays HTML out across its viewport even when it has native padding.
            // Its parent must size the viewport to the unobstructed rectangle.
            surface.setOnApplyWindowInsetsListener((view,insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(surface);
        surface.requestApplyInsets();
        web.loadUrl(BASE + "index.html");
    }

    final class Bridge {
        @JavascriptInterface public boolean isTelevision() {
            return getPackageManager().hasSystemFeature("android.software.leanback");
        }
        @JavascriptInterface public void layout(boolean tv) {
            runOnUiThread(() -> {
                tvMode = tv;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController controller = getWindow().getInsetsController();
                    if (controller != null) {
                        if (tv) {
                            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                            controller.hide(WindowInsets.Type.systemBars());
                        } else {
                            controller.show(WindowInsets.Type.systemBars());
                        }
                    }
                    getWindow().getDecorView().requestApplyInsets();
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(tv ? 5894 : 0);
                }
                setRequestedOrientation(tv ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            });
        }
        @JavascriptInterface public String mirror() { return getPreferences(0).getString("mirror",Catalog.DEFAULT); }
        @JavascriptInterface public String history() { return History.all(MainActivity.this).toString(); }
        @JavascriptInterface public void forgetViewing(String page) { History.forget(MainActivity.this,page); }
        @JavascriptInterface public void clearHistory() { History.clear(MainActivity.this); }
        @JavascriptInterface public void setProfile(String id) {
            String safe = id == null || !id.matches("[a-z0-9-]{1,24}") ? "main" : id;
            getSharedPreferences("settings",0).edit().putString("profile",safe).commit();
        }
        @JavascriptInterface public void syncFavorites(String json) { EpisodeAlerts.syncFavorites(MainActivity.this,json); }
        @JavascriptInterface public boolean episodeAlerts() { return EpisodeAlerts.enabled(MainActivity.this); }
        @JavascriptInterface public void setEpisodeAlerts(boolean on) {
            EpisodeAlerts.setEnabled(MainActivity.this,on);
            if (on && android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                runOnUiThread(() -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 44));
        }
        /** Favourites as a file to keep or move to another device (share sheet). */
        @JavascriptInterface public void shareText(String title,String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT,text).putExtra(Intent.EXTRA_SUBJECT,title);
                try { startActivity(Intent.createChooser(send,title)); } catch (android.content.ActivityNotFoundException ignored) { }
            });
        }
        @JavascriptInterface public void pickImport() {
            runOnUiThread(() -> {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(pick,45); } catch (android.content.ActivityNotFoundException e) {
                    web.evaluateJavascript("window.importResult && importResult('', 'На устройстве нет выбора файлов. Вставьте текст вручную.')",null);
                }
            });
        }
        /** A title opened from the Android TV "Play next" row, handed to the page once. */
        @JavascriptInterface public String takeLaunch() {
            String value = pendingLaunch; pendingLaunch = null; return value == null ? "" : value;
        }
        @JavascriptInterface public boolean canVoiceSearch() {
            return new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(getPackageManager()) != null;
        }
        @JavascriptInterface public void voiceSearch() {
            runOnUiThread(() -> {
                Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                        .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Скажите название фильма или сериала");
                try { startActivityForResult(i, 43); } catch (android.content.ActivityNotFoundException ignored) { }
            });
        }
        @JavascriptInterface public boolean maximumQuality() { return VideoQuality.maximum(MainActivity.this); }
        @JavascriptInterface public void setMaximumQuality(boolean value) {
            VideoQuality.preferences(MainActivity.this).edit().putBoolean(VideoQuality.MAXIMUM,value).apply();
        }
        @JavascriptInterface public int savedQuality(String season) {
            return VideoQuality.preferences(MainActivity.this).getInt(VideoQuality.seasonKey(season),VideoQuality.ASK);
        }
        @JavascriptInterface public void forgetQuality(String season) {
            VideoQuality.preferences(MainActivity.this).edit().remove(VideoQuality.seasonKey(season)).apply();
        }
        @JavascriptInterface public void setMirror(String raw) {
            String origin = Catalog.origin(raw);
            getPreferences(0).edit().putString("mirror",origin).apply();
        }
        @JavascriptInterface public void request(int id,String method,String data) {
            (method.equals("detail") || method.equals("kinopoisk") ? details : network).execute(() -> {
                JSONObject result = null;
                String error = "";
                try {
                    JSONObject a = new JSONObject(data);
                    if (method.equals("detail")) result = Catalog.details(a.getString("url"));
                    else if (method.equals("kinopoisk")) result = Rukino.lookup(a.getString("path"));
                    else if (method.equals("collaps")) result = PlaybackSources.collaps(a);
                    else if (method.equals("vibix")) result = PlaybackSources.vibix(a);
                    else if (method.equals("torrents")) result = PlaybackSources.torrents(a);
                    else if (method.equals("update")) result = Updater.check(MainActivity.this);
                    else if (method.equals("install")) { Updater.install(MainActivity.this,a.getString("url")); result = new JSONObject().put("ok",true); }
                    else result = Catalog.list(a.optString("mirror",mirror()),a.optString("page"),a.optString("query"));
                } catch (Exception e) {
                    if (e instanceof org.jsoup.HttpStatusException)
                        error = "Сайт ответил HTTP " + ((org.jsoup.HttpStatusException)e).getStatusCode() + ". Попробуйте другое зеркало.";
                    else if (e instanceof javax.net.ssl.SSLException)
                        error = "Android не смог подтвердить сертификат зеркала. Выберите другой HTTPS-адрес.";
                    else if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException)
                        error = "Не удалось связаться с зеркалом. Проверьте сеть или выберите другой адрес.";
                    else error = e.getMessage() != null ? e.getMessage() : "Не удалось загрузить страницу";
                }
                String js = "window.reply(" + id + "," + JSONObject.quote(error) + "," + (result == null ? "null" : JSONObject.quote(result.toString())) + ")";
                runOnUiThread(() -> { if (!isDestroyed()) web.evaluateJavascript(js,null); });
            });
        }
        @JavascriptInterface public void play(String item,String detail,int selected,boolean external) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this,PlayerActivity.class);
                i.putExtra("tv",tvMode).putExtra("item",item).putExtra("detail",detail).putExtra("selected",selected).putExtra("external",external);
                startActivityForResult(i,42);
            });
        }
        @JavascriptInterface public void openTorrent(String magnet) {
            if (!PlaybackSources.validMagnet(magnet)) return;
            runOnUiThread(() -> {
                Intent target = new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(magnet));
                try {
                    if (target.resolveActivity(getPackageManager()) != null) {
                        startActivity(Intent.createChooser(target,"Открыть раздачу"));
                        return;
                    }
                } catch (android.content.ActivityNotFoundException ignored) { }
                new android.app.AlertDialog.Builder(MainActivity.this).setTitle("Нужен торрент-клиент")
                        .setMessage("Откройте magnet-ссылку в приложении с поддержкой торрентов.")
                        .setPositiveButton("Скопировать ссылку",(dialog,which)->{
                            android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Magnet",magnet));
                        }).setNegativeButton("Назад",null).show();
            });
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode==42 && resultCode==RESULT_OK && data!=null && data.getBooleanExtra("otherSites",false))
            web.evaluateJavascript("window.otherSites && otherSites("+data.getBooleanExtra("external",false)+")",null);
        else if (requestCode==43 && resultCode==RESULT_OK && data!=null) {
            java.util.ArrayList<String> heard = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (heard != null && !heard.isEmpty())
                web.evaluateJavascript("window.voiceResult && voiceResult(" + JSONObject.quote(heard.get(0)) + ")", null);
        }
        else if (requestCode==45 && resultCode==RESULT_OK && data!=null && data.getData()!=null) {
            String text = "";
            try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
                byte[] bytes = in.readNBytes(2_000_000); text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
            web.evaluateJavascript("window.importResult && importResult(" + JSONObject.quote(text) + ",'')", null);
        }
        else if (requestCode==42 && resultCode==RESULT_OK && data!=null && data.getBooleanExtra("nextEpisode",false))
            web.evaluateJavascript("window.playNextEpisode && playNextEpisode()",null);
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript("typeof back==='function' && back()", value -> { if (!"true".equals(value)) super.onBackPressed(); });
    }
    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.refreshHistory && refreshHistory()",null);
    }
    @Override protected void onDestroy() {
        network.shutdownNow();
        details.shutdownNow();
        web.removeJavascriptInterface("Cinema");
        web.destroy();
        super.onDestroy();
    }
}
