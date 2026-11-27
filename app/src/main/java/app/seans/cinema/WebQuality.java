package app.seans.cinema;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Scanner;
import java.util.Set;

/** A single pre-play request. Only the selected HTTPS provider can report its quality menu. */
final class WebQuality {
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final VideoQuality quality;
    private final TextView cover;
    private final FrameLayout screen;
    private final Runnable otherSources,timeout=this::failed;
    private JavaScriptReplyProxy provider;
    private int selected=-1;
    private boolean finished,waitingForChoice,startPending,resumed;
    private AlertDialog error;

    WebQuality(Activity activity,FrameLayout screen,VideoQuality quality,Runnable otherSources) {
        this.activity=activity;this.screen=screen;this.quality=quality;this.otherSources=otherSources;
        cover=new TextView(activity);cover.setGravity(Gravity.CENTER);cover.setTextColor(Color.WHITE);
        cover.setBackgroundColor(Color.BLACK);cover.setText("Проверяю доступное качество…");
        cover.setClickable(true);cover.setFocusableInTouchMode(true);
        screen.addView(cover,new FrameLayout.LayoutParams(-1,-1));cover.requestFocus();
    }
    void install(WebView web,boolean hub) {
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
                !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)){
            handler.post(this::failed);return;
        }
        Set<String> origin=Collections.singleton(hub?"https://player.cdnvideohub.com":"https://api.embf.net");
        try(InputStream in=activity.getAssets().open("web-quality.js");Scanner reader=new Scanner(in,"UTF-8")){
            WebViewCompat.addWebMessageListener(web,"SeansVideoQuality",origin,(view,msg,sender,main,reply)->{
                if(finished || hub==main || !origin.contains(sender.toString()) || msg.getType()!=androidx.webkit.WebMessageCompat.TYPE_STRING)return;
                String data=msg.getData();
                if(data==null || data.length()>2048)return;
                try{
                    JSONObject payload=new JSONObject(data);
                    if("ready".equals(payload.optString("type"))){
                        if(provider!=null)return;
                        provider=reply;provider.postMessage("{\"action\":\"prepare\"}");return;
                    }
                    if(provider!=reply)return;
                    switch(payload.optString("type")){
                        case "options":
                            if(waitingForChoice || selected>=0)return;
                            JSONArray rows=payload.getJSONArray("heights");
                            ArrayList<Integer> heights=new ArrayList<>();
                            for(int i=0;i<Math.min(48,rows.length());i++){
                                int h=rows.optInt(i);
                                if(h>0 && h<=16384 && !heights.contains(h))heights.add(h);
                            }
                            if(heights.isEmpty()){failed();return;}
                            waitingForChoice=true;handler.removeCallbacks(timeout);
                            quality.choose(heights,payload.optInt("current"),false,"Как в плеере",h->{
                                if(finished)return;
                                selected=h;provider.postMessage("{\"action\":\"select\",\"height\":"+h+"}");
                                handler.postDelayed(timeout,12000);
                            },activity::finish);
                            break;
                        case "applied":
                            if(selected<0 || selected!=payload.optInt("height",-1))return;
                            finished=true;handler.removeCallbacks(timeout);screen.removeView(cover);
                            startPending=true;setResumed(resumed);break;
                        case "error": failed();break;
                    }
                }catch(org.json.JSONException ignored){ }
            });
            WebViewCompat.addDocumentStartJavaScript(web,reader.useDelimiter("\\A").next(),origin);
            handler.postDelayed(timeout,20000);
        }catch(Exception e){handler.post(this::failed);}
    }
    boolean blocking(){return !finished;}
    void setResumed(boolean value){
        resumed=value;
        if(value && startPending && provider!=null){
            startPending=false;provider.postMessage("{\"action\":\"start\"}");
        }
    }
    private void failed(){
        if(finished || error!=null || activity.isFinishing() || activity.isDestroyed())return;
        finished=true;handler.removeCallbacks(timeout);
        error=new AlertDialog.Builder(activity).setTitle("Качество выбирается в плеере сайта")
                .setMessage("Плеер не подтвердил доступные настройки видео.")
                .setPositiveButton("Открыть плеер",(d,w)->{
                    if(provider!=null)provider.postMessage("{\"action\":\"release\"}");
                    screen.removeView(cover);
                }).setNeutralButton("Другой источник",(d,w)->otherSources.run())
                .setNegativeButton("Назад",(d,w)->activity.finish()).setOnCancelListener(d->activity.finish()).show();
    }
    void close(){
        finished=true;startPending=false;handler.removeCallbacksAndMessages(null);
        if(error!=null)error.dismiss();
        if(cover.getParent()==screen)screen.removeView(cover);
        provider=null;
    }
}
