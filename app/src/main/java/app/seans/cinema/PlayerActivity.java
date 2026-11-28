package app.seans.cinema;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class PlayerActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout screen;
    private LinearLayout tools;
    private TextView status;
    private WebView browser;
    private ExoPlayer player;
    private PlayerView playerView;
    private VideoQuality quality;
    private WebQuality webQuality;
    private JSONObject item, detail;
    private JSONArray sources;
    private int selected, generation;
    private boolean external, started, completed, resumePlay, foreground, chooserOpened;
    private boolean webPlaybackActivated, collapsPlayback, vibixStarted, webFailed;
    private final Runnable hideWebTools = () -> {
        if (browser!=null && tools!=null && !webFailed) {
            if (tools.hasFocus()) browser.requestFocus();
            tools.setVisibility(View.GONE);
        }
    };
    private TextView cursor;
    private String stream = "", mime = "";
    private Map<String,String> headers = new HashMap<>();
    private long seek;
    private final Runnable save = new Runnable() {
        public void run() { record(); if (player != null) ui.postDelayed(this,5000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("tv",false)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        try {
            item = new JSONObject(getIntent().getStringExtra("item"));
            detail = new JSONObject(getIntent().getStringExtra("detail"));
            quality = new VideoQuality(this,detail);
            sources = detail.getJSONArray("players");
            selected = state == null ? getIntent().getIntExtra("selected",0) : state.getInt("source");
            external = getIntent().getBooleanExtra("external",false);
            seek = History.position(this,detail.getString("page"));
        } catch (Exception e) { finish(); return; }
        screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        setContentView(screen);
        resolve();
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void toolbar(boolean showStatus) {
        tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(8),dp(4),dp(8),dp(4));
        tools.setBackgroundColor(0xde181b19);
        Button back = button("Назад"); back.setOnClickListener(v -> finish()); tools.addView(back);
        status = new TextView(this); status.setTextColor(0xfff3eee5); status.setTextSize(14);
        status.setPadding(dp(8),0,dp(6),0);
        tools.addView(status,new LinearLayout.LayoutParams(0,-2,1));
        Button source = button("Источник"); source.setOnClickListener(v -> chooseSource()); tools.addView(source);
        screen.addView(tools,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        status.setText(showStatus ? "Открываю плеер…" : detail.optString("title"));
    }
    private Button button(String label) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setTextSize(13); return button;
    }
    private void chooseSource() {
        if(detail.optBoolean("seriesChoice")) { otherSources(); return; }
        String[] labels = new String[sources.length()];
        for (int i=0;i<labels.length;i++) labels[i] = sources.optJSONObject(i).optString("label");
        new AlertDialog.Builder(this).setTitle("Источник видео").setSingleChoiceItems(labels,selected,(dialog,which)-> {
            dialog.dismiss(); selected=which; resolve();
        }).setNeutralButton("Другие сайты",(d,w)->otherSources()).setNegativeButton("Закрыть",null).show();
    }
    private void otherSources() {
        setResult(RESULT_OK,new Intent().putExtra("otherSites",true).putExtra("external",external));
        finish();
    }
    private String sourcePage() {
        return detail.optString("sourcePage",detail.optString("page"));
    }
    private void showWebTools() {
        if(browser==null || tools==null)return;
        tools.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideWebTools);
        if(!webFailed)ui.postDelayed(hideWebTools,4500);
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void resolve() {
        quality.close();
        releasePlayer(); destroyBrowser(); screen.removeAllViews();
        stream=""; chooserOpened=false; completed=false; started=false; webPlaybackActivated=false; vibixStarted=false; webFailed=false;
        final int attempt=++generation;
        JSONObject source=sources.optJSONObject(selected);
        if (source==null) { finish(); return; }
        String url=Catalog.https(source.optString("url"));
        if (url.isEmpty()) { toolbar(true); status.setText("Источник недоступен"); return; }
        if ("direct".equals(source.optString("type"))) {
            Map<String,String> requestHeaders=new HashMap<>();
            requestHeaders.put("Referer",sourcePage().split("#",2)[0]);
            toolbar(true); accept(attempt,url,mediaType(Uri.parse(url)),requestHeaders); return;
        }
        if ("kinonox".equals(source.optString("type")) || "kinonoxWeb".equals(source.optString("type"))) {
            toolbar(true);
            new Thread(()->{
                try {
                    String frame=ExtraCatalog.kinonoxFrame(url);
                    if ("kinonoxWeb".equals(source.optString("type")) && !PlaybackSources.isVibixFrame(frame))
                        throw new java.io.IOException("Источник изменился");
                    ui.post(()->{
                        if (attempt!=generation || isFinishing() || isDestroyed()) return;
                        screen.removeAllViews(); openWebPlayer(attempt,source,frame);
                    });
                } catch (Exception e) {
                    ui.post(()->{if (attempt==generation && !isDestroyed()) status.setText("Плеер недоступен. Выберите другой источник.");});
                }
            }).start();
            return;
        }
        openWebPlayer(attempt,source,url);
    }
    @SuppressLint("SetJavaScriptEnabled")
    private void openWebPlayer(int attempt,JSONObject source,String url) {
        boolean widgetMenu="widgetMenu".equals(source.optString("type"));
        boolean website=widgetMenu || "site".equals(source.optString("type")) || "kinonoxWeb".equals(source.optString("type"));
        browser=new WebView(this);
        browser.setBackgroundColor(Color.BLACK);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setAllowFileAccess(false);
        browser.getSettings().setAllowContentAccess(false);
        browser.getSettings().setMediaPlaybackRequiresUserGesture(false);
        browser.getSettings().setSupportMultipleWindows(false);
        browser.setWebChromeClient(new WebChromeClient());
        final String ua=browser.getSettings().getUserAgentString();
        browser.setWebViewClient(new WebViewClient() {
            private void failed(WebResourceRequest r) {
                if(attempt!=generation || browser==null || !r.isForMainFrame())return;
                if(detail.optBoolean("reusedSource") && !isFinishing()){ otherSources(); return; }
                webFailed=true;showWebTools();status.setText("Плеер недоступен. Выберите другой источник.");
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, android.webkit.WebResourceError e){failed(r);}
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse response){failed(r);}
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (r.isForMainFrame() && "Zona".equals(source.optString("label")) && Catalog.localLink(WebCatalog.ZONA,r.getUrl().toString())) return false;
                return r.isForMainFrame() || !"https".equals(r.getUrl().getScheme()) || advertising(r.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u=r.getUrl();
                if (!"https".equals(u.getScheme()) || advertising(u))
                    return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                String type=mediaType(u);
                if (!website && !type.isEmpty() && !MimeTypes.VIDEO_MP4.equals(type) && "GET".equals(r.getMethod())) {
                    Map<String,String> h=new HashMap<>();
                    h.put("User-Agent",ua);
                    for (Map.Entry<String,String> e:r.getRequestHeaders().entrySet())
                        if (e.getKey().equalsIgnoreCase("Referer") || e.getKey().equalsIgnoreCase("Origin")) h.put(e.getKey(),e.getValue());
                    ui.post(()->accept(attempt,u.toString(),type,h));
                }
                return null;
            }
        });
        toolbar(true);
        if (website && getIntent().getBooleanExtra("tv",false)) {
            // Keep the site's own controls below the app toolbar on TV.
            LinearLayout webLayout=new LinearLayout(this);
            webLayout.setOrientation(LinearLayout.VERTICAL);
            screen.removeView(tools);
            webLayout.addView(tools,new LinearLayout.LayoutParams(-1,-2));
            webLayout.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
            screen.addView(webLayout,new FrameLayout.LayoutParams(-1,-1));
        } else {
            screen.addView(browser,0,new FrameLayout.LayoutParams(-1,-1));
        }
        showWebTools();
        if (website) {
            status.setText("Плеер сайта · "+source.optString("label"));
            if(detail.optBoolean("seriesChoice"))History.opened(this,item,detail.optString("page"),detail.optString("title"));
            String label=source.optString("label");
            if("Collaps".equals(label)){
                Button audio=button("Озвучка");tools.addView(audio,tools.getChildCount()-1);
                audio.setOnClickListener(v->AudioMenu.collaps(this,browser));
            }else if(getIntent().getBooleanExtra("tv",false)){
                Button pointer=button("Курсор");tools.addView(pointer,tools.getChildCount()-1);
                pointer.setOnClickListener(v->enableCursor());
            }
            if (getIntent().getBooleanExtra("tv",false) && ("Collaps".equals(label) || "Vibix".equals(label))) {
                collapsPlayback="Collaps".equals(label);
                Button playback=button(collapsPlayback ? "Пуск / пауза" : "Запустить видео");
                playback.setFocusableInTouchMode(true);
                tools.addView(playback,tools.getChildCount()-1);
                playback.setOnClickListener(v->{
                    if (collapsPlayback) togglePageVideo();
                    else { browser.requestFocus(); tapPlayerCenter(); vibixStarted=true; }
                    webPlaybackActivated=true;
                    showWebTools();
                });
                webPlaybackActivated=true;
                playback.requestFocus();
            } else browser.requestFocus();
            if((widgetMenu || "Collaps".equals(label)) && quality.beforePlayback()){
                webQuality=new WebQuality(this,screen,quality,this::otherSources);
                webQuality.install(browser,widgetMenu);webQuality.setResumed(foreground);
            }
            if(!widgetMenu){browser.loadUrl(url);return;}
        }
        String attr=TextUtils.htmlEncode(url);
        String content;
        if (widgetMenu || "widget".equals(source.optString("type"))) {
            String season = source.has("season") ? " season=\""+escape(source,"season")+"\"" : "";
            String episode = source.has("episode") ? " episode=\""+escape(source,"episode")+"\"" : "";
            String tv = getIntent().getBooleanExtra("tv",false) ? " tv-mode=\"true\"" : "";
            content="<script defer src=\""+attr+"\"></script><video-player data-title-id=\""+escape(source,"titleId")+
                    "\" data-publisher-id=\""+escape(source,"publisher")+"\" data-aggregator=\""+escape(source,"aggregator")+
                    "\" is-show-banner=\"false\""+season+episode+tv+"></video-player>";
        } else {
            content="<iframe sandbox=\"allow-scripts allow-same-origin allow-forms allow-presentation\" allow=\"autoplay; fullscreen\" src=\""+attr+"\"></iframe>";
        }
        browser.loadDataWithBaseURL(sourcePage(),"<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>*{box-sizing:border-box}html,body,iframe,video-player{margin:0;width:100%;height:100%;border:0;background:#080908;display:block}</style></head><body>"+content+"</body></html>","text/html","UTF-8",null);
        browser.requestFocus();
        if(widgetMenu)return;
        ui.postDelayed(()->{if(attempt==generation && stream.isEmpty()) status.setText("Нажмите ▶ в плеере");},3000);
        ui.postDelayed(()->{if(attempt==generation && stream.isEmpty()) status.setText(external ? "Нет внешней ссылки. Выберите другой источник." : "Плеер сайта");},25000);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if(webQuality!=null && webQuality.blocking() && key!=KeyEvent.KEYCODE_BACK)return true;
        if(cursor!=null && handleCursor(event))return true;
        if(browser!=null && tools!=null){
            if(key==KeyEvent.KEYCODE_DPAD_UP && tools.getVisibility()!=View.VISIBLE){
                showWebTools();tools.getChildAt(tools.getChildCount()-1).requestFocus();return true;
            }
            if(event.getAction()==KeyEvent.ACTION_DOWN)showWebTools();
        }
        if (webPlaybackActivated && browser!=null && browser.hasFocus()) {
            if (key>=KeyEvent.KEYCODE_DPAD_UP && key<=KeyEvent.KEYCODE_DPAD_RIGHT) webPlaybackActivated=false;
            else if (key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (collapsPlayback) {
                    if (event.getAction()==KeyEvent.ACTION_UP) togglePageVideo();
                    return true;
                }
                if (!vibixStarted) {
                    if (event.getAction()==KeyEvent.ACTION_UP) { tapPlayerCenter(); vibixStarted=true; }
                    return true;
                }
                return super.dispatchKeyEvent(new KeyEvent(event.getDownTime(),event.getEventTime(),event.getAction(),
                        KeyEvent.KEYCODE_SPACE,event.getRepeatCount(),event.getMetaState(),event.getDeviceId(),0,event.getFlags(),event.getSource()));
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        boolean handled=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP)showWebTools();
        return handled;
    }
    private void enableCursor() {
        if(browser==null || cursor!=null)return;
        cursor=new TextView(this);cursor.setText("⊕");cursor.setGravity(Gravity.CENTER);
        cursor.setTextSize(24);cursor.setTextColor(Color.WHITE);cursor.setShadowLayer(4,0,0,Color.BLACK);
        screen.addView(cursor,new FrameLayout.LayoutParams(dp(38),dp(38)));
        cursor.setX((browser.getWidth()-dp(38))/2f);cursor.setY((browser.getHeight()-dp(38))/2f);
        ui.removeCallbacks(hideWebTools);tools.setVisibility(View.GONE);browser.requestFocus();
        android.widget.Toast.makeText(this,"Стрелки — курсор, OK — нажать, Назад — выйти",android.widget.Toast.LENGTH_SHORT).show();
    }
    private boolean handleCursor(KeyEvent event) {
        int key=event.getKeyCode();
        if(key==KeyEvent.KEYCODE_BACK){
            if(event.getAction()==KeyEvent.ACTION_UP){screen.removeView(cursor);cursor=null;showWebTools();tools.getChildAt(tools.getChildCount()-1).requestFocus();}
            return true;
        }
        if(key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_DPAD_CENTER){
            if(event.getAction()==KeyEvent.ACTION_UP)tapPlayer(cursor.getX()+cursor.getWidth()/2f,cursor.getY()+cursor.getHeight()/2f);
            return true;
        }
        if(key<KeyEvent.KEYCODE_DPAD_UP || key>KeyEvent.KEYCODE_DPAD_RIGHT)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            int step=dp(event.getRepeatCount()>3?20:8);
            float x=cursor.getX()+(key==KeyEvent.KEYCODE_DPAD_LEFT?-step:key==KeyEvent.KEYCODE_DPAD_RIGHT?step:0);
            float y=cursor.getY()+(key==KeyEvent.KEYCODE_DPAD_UP?-step:key==KeyEvent.KEYCODE_DPAD_DOWN?step:0);
            float radius=cursor.getWidth()/2f;
            cursor.setX(Math.max(1-radius,Math.min(x,browser.getWidth()-radius-1)));
            cursor.setY(Math.max(1-radius,Math.min(y,browser.getHeight()-radius-1)));
        }
        return true;
    }
    private void togglePageVideo() {
        browser.evaluateJavascript("(()=>{const v=document.querySelector('video');if(v){if(v.paused)v.play().catch(()=>{});else v.pause();}})()",null);
    }
    // Vibix exposes its initial play control in the middle of a cross-origin iframe.
    private void tapPlayerCenter() {
        if (browser==null || browser.getWidth()==0 || browser.getHeight()==0) return;
        tapPlayer(browser.getWidth()/2f,browser.getHeight()/2f);
    }
    private void tapPlayer(float x,float y) {
        long now=SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            browser.dispatchTouchEvent(event);
            event.recycle();
        }
    }
    private static String escape(JSONObject p,String key) {return TextUtils.htmlEncode(p.optString(key));}
    private static boolean advertising(Uri u) {
        String h=String.valueOf(u.getHost()).toLowerCase(Locale.ROOT), path=String.valueOf(u.getPath()).toLowerCase(Locale.ROOT);
        return h.matches("(?:.*\\.)?(?:doubleclick\\.net|googlesyndication\\.com|adfox\\.ru|adriver\\.ru|googletagmanager\\.com|an\\.yandex\\.ru)")
                || path.matches(".*(?:/(?:adfox|load-xml|vast|vpaid|preroll|midroll|postroll|adserver|popunder|ads)(?:[./_-]|$)).*");
    }
    private static String mediaType(Uri u) {
        String p=String.valueOf(u.getPath()).toLowerCase(Locale.ROOT);
        if (p.endsWith(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (p.endsWith(".mpd")) return MimeTypes.APPLICATION_MPD;
        // VideoHub also publishes DASH manifests on OK CDN with an extensionless path.
        String host=String.valueOf(u.getHost()).toLowerCase(Locale.ROOT);
        if (host.endsWith(".okcdn.ru") && "1".equals(u.getQueryParameter("type"))
                && "6".equals(u.getQueryParameter("ct")) && u.getQueryParameter("bytes")==null)
            return MimeTypes.APPLICATION_MPD;
        if (p.endsWith(".mp4") && !p.matches(".*(?:/seg[-_]|/init[-_.]|/chunk[-_]).*")) return MimeTypes.VIDEO_MP4;
        return "";
    }
    private void accept(int attempt,String url,String type,Map<String,String> h) {
        if (isFinishing() || isDestroyed() || attempt!=generation || !stream.isEmpty()) return;
        stream=url; mime=type; headers=h;
        destroyBrowser();
        if (!foreground) return;
        playResolvedStream();
    }
    private void playResolvedStream() {
        if (chooserOpened) return;
        Intent intent=new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(stream),"video/*");
        intent.putExtra("title",detail.optString("title")).putExtra("position",(int)Math.min(Integer.MAX_VALUE,seek));
        Bundle bundle=new Bundle(); java.util.ArrayList<String> pairs=new java.util.ArrayList<>();
        for (Map.Entry<String,String> e:headers.entrySet()) {
            bundle.putString(e.getKey(),e.getValue()); pairs.add(e.getKey()); pairs.add(e.getValue());
        }
        intent.putExtra("headers",pairs.toArray(new String[0]));
        intent.putExtra("return_result",true);
        intent.putExtra("com.android.browser.headers",bundle);
        try {
            startActivityForResult(intent,51);
            chooserOpened=true;
        } catch (ActivityNotFoundException e) {
            playNative();
        }
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if (code==51) {
            if (result==RESULT_OK && data!=null && data.getExtras()!=null) {
                Bundle info=data.getExtras();
                Object time=info.get(info.containsKey("extra_position") ? "extra_position" : "position");
                Object length=info.get(info.containsKey("extra_duration") ? "extra_duration" : "duration");
                if (time instanceof Number) {
                    long ms=Math.max(0,((Number)time).longValue());
                    long duration=length instanceof Number ? ((Number)length).longValue() : 0L;
                    boolean ended=duration>0 && ms>=duration;
                    History.save(this,item,detail.optString("page"),detail.optString("title"),ended ? 0 : ms,duration,ended);
                }
            }
            finish();
        }
    }
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void playNative() {
        if (player!=null) return;
        screen.removeAllViews();
        playerView=new PlayerView(this); screen.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        playerView.setShowSubtitleButton(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        toolbar(false);
        Button resolution=button("Качество");tools.addView(resolution,tools.getChildCount()-1);
        resolution.setOnClickListener(v->nativeQuality(true));
        Button audio=button("Озвучка");tools.addView(audio,tools.getChildCount()-1);
        audio.setOnClickListener(v->AudioMenu.nativeTracks(this,player));
        Button skip=button("+85 с");tools.addView(skip,tools.getChildCount()-1);
        skip.setOnClickListener(v->{if(player!=null)player.seekTo(Math.min(player.getCurrentPosition()+85000,Math.max(0,player.getDuration()-1000)));});
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility-> tools.setVisibility(visibility));
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent(headers.getOrDefault("User-Agent",Catalog.UA))
                .setDefaultRequestProperties(headers).setConnectTimeoutMs(12000).setReadTimeoutMs(18000);
        player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
        player.setAudioAttributes(AudioAttributes.DEFAULT,true);
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        MediaItem.Builder media=new MediaItem.Builder().setUri(stream);
        if (!mime.isEmpty()) media.setMimeType(mime);
        player.setMediaItem(media.build(),seek);
        boolean gate=quality.beforePlayback();
        if(gate){playerView.setUseController(false);tools.setVisibility(View.GONE);}
        player.addListener(new Player.Listener() {
            private boolean qualityRequested;
            @Override public void onPlaybackStateChanged(int state) {
                if(state==Player.STATE_READY && gate && !qualityRequested){
                    qualityRequested=true;nativeQuality(false);
                }
                if (state==Player.STATE_ENDED) {
                    completed=true; seek=0; record();
                    if (detail.optBoolean("seriesChoice")) nextEpisodeCountdown(); else playerView.showController();
                }
            }
            @Override public void onIsPlayingChanged(boolean playing){
                if(playing && !started){started=true;save.run();}
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (isFinishing() || isDestroyed()) return;
                if (!started && detail.optBoolean("reusedSource")) { otherSources(); return; }
                new AlertDialog.Builder(PlayerActivity.this).setTitle("Не удалось воспроизвести видео")
                        .setMessage("Видеосервер не отдал поток или формат недоступен. "+e.getErrorCodeName())
                        .setPositiveButton("Другой источник",(d,w)->chooseSource())
                        .setNeutralButton("Повторить",(d,w)->resolve())
                        .setNegativeButton("Назад",(d,w)->finish()).show();
            }
        });
        player.prepare(); if(!gate)player.play(); playerView.requestFocus();
    }
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    private void nativeQuality(boolean manual){
        if(player==null)return;
        ExoPlayer target=player;
        TreeMap<Integer,TrackSelectionOverride> tracks=VideoQuality.tracks(target);
        Format current=target.getVideoFormat();
        boolean fixed=target.getTrackSelectionParameters().overrides.values().stream().anyMatch(o->o.getType()==C.TRACK_TYPE_VIDEO);
        int selectedHeight=fixed && current!=null?current.height:0;
        quality.choose(new ArrayList<>(tracks.keySet()),selectedHeight,manual,"Авто",height->{
            if(target!=player || isFinishing())return;
            TrackSelectionParameters.Builder selection=target.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            if(tracks.containsKey(height))selection.setOverrideForType(tracks.get(height));
            target.setTrackSelectionParameters(selection.build());
            if(!manual){
                playerView.setUseController(true);
                if(foreground)target.play();else resumePlay=true;
            }
        },()->{if(!manual)finish();});
    }
    // At the end of an episode: count down 10 seconds, then hand over to the catalogue to start the next one.
    private void nextEpisodeCountdown() {
        android.widget.LinearLayout box=new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);box.setPadding(dp(24),dp(18),dp(24),dp(18));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xEE1D1F20);bg.setCornerRadius(dp(18));box.setBackground(bg);
        TextView text=new TextView(this);text.setTextColor(0xFFFFFFFF);text.setTextSize(20);box.addView(text);
        android.widget.LinearLayout row=new android.widget.LinearLayout(this);row.setPadding(0,dp(14),0,0);
        Button now=button("Смотреть сейчас"), stop=button("Отмена");row.addView(now);row.addView(stop);box.addView(row);
        screen.addView(box,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END){{setMargins(0,0,dp(32),dp(48));}});
        final int[] left={10};
        Runnable[] tick=new Runnable[1];
        Runnable go=()->{setResult(RESULT_OK,new Intent().putExtra("nextEpisode",true));finish();};
        tick[0]=()->{
            if(isFinishing())return;
            if(left[0]<=0){go.run();return;}
            text.setText("Следующая серия через "+left[0]+" с");left[0]--;ui.postDelayed(tick[0],1000);
        };
        now.setOnClickListener(v->{ui.removeCallbacks(tick[0]);go.run();});
        stop.setOnClickListener(v->{ui.removeCallbacks(tick[0]);screen.removeView(box);playerView.showController();});
        tick[0].run();now.requestFocus();
    }
    private void record() {
        if (player!=null && started) {
            seek=completed ? 0 : player.getCurrentPosition();
            History.save(this,item,detail.optString("page"),detail.optString("title"),seek,player.getDuration(),completed);
        }
    }
    private void destroyBrowser() {
        if(webQuality!=null){webQuality.close();webQuality=null;}
        ui.removeCallbacks(hideWebTools);
        if(cursor!=null){screen.removeView(cursor);cursor=null;}
        if (browser==null) return;
        WebView v=browser; browser=null;
        if (v.getParent() instanceof ViewGroup) ((ViewGroup)v.getParent()).removeView(v);
        v.stopLoading(); v.destroy();
    }
    private void releasePlayer() {
        ui.removeCallbacks(save); record();
        if (player!=null) { playerView.setPlayer(null); player.release(); player=null; }
    }
    @Override protected void onPause() {
        foreground=false;
        if(webQuality!=null)webQuality.setResumed(false);
        if (browser!=null) browser.onPause();
        if (player!=null) { resumePlay=player.getPlayWhenReady(); record(); player.pause(); }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); foreground=true;
        if(webQuality!=null)webQuality.setResumed(true);
        if (browser!=null) browser.onResume();
        if (player!=null && resumePlay) player.play();
        else if (player==null && !stream.isEmpty()) playResolvedStream();
    }
    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putInt("source",selected); record(); }
    @Override protected void onDestroy() {
        if(quality!=null)quality.close();
        ++generation; ui.removeCallbacksAndMessages(null); releasePlayer(); destroyBrowser(); super.onDestroy();
    }
}
