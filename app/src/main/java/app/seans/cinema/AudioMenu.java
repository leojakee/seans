package app.seans.cinema;

import android.app.Activity;
import android.app.AlertDialog;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

final class AudioMenu {
    static void nativeTracks(Activity activity,Player player) {
        if(player==null)return;
        ArrayList<String> names=new ArrayList<>();
        ArrayList<TrackSelectionOverride> choices=new ArrayList<>();
        int selected=-1;
        for(Tracks.Group group:player.getCurrentTracks().getGroups()){
            if(group.getType()!=C.TRACK_TYPE_AUDIO)continue;
            for(int i=0;i<group.length;i++){
                if(!group.isTrackSupported(i))continue;
                Format f=group.getTrackFormat(i);
                String name=f.label;
                String language=f.language==null || "und".equals(f.language)?"":Locale.forLanguageTag(f.language).getDisplayLanguage(new Locale("ru"));
                if(name==null || name.isEmpty())name=language.isEmpty()?"Дорожка "+(names.size()+1):language;
                else if(!language.isEmpty() && !name.equalsIgnoreCase(language))name+=" · "+language;
                if(group.isTrackSelected(i))selected=names.size();
                choices.add(new TrackSelectionOverride(group.getMediaTrackGroup(),i));names.add(name);
            }
        }
        if(names.isEmpty()){unavailable(activity);return;}
        new AlertDialog.Builder(activity).setTitle("Озвучка").setSingleChoiceItems(names.toArray(new String[0]),selected,(d,i)->{
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setOverrideForType(choices.get(i)).build());
            d.dismiss();
        }).setNegativeButton("Закрыть",null).show();
    }
    static void collaps(Activity activity,WebView web) {
        String script;
        try(java.io.InputStream in=activity.getAssets().open("web-audio.js"); java.util.Scanner scanner=new java.util.Scanner(in,"UTF-8")){
            script=scanner.useDelimiter("\\A").next();
        }catch(Exception e){unavailable(activity);return;}
        web.evaluateJavascript(script+"(null)",value->{
            if(activity.isFinishing() || !web.isAttachedToWindow())return;
            try{
                JSONArray rows=new JSONArray(value);if(rows.length()==0){unavailable(activity);return;}
                String[] labels=new String[rows.length()];int selected=-1;
                for(int i=0;i<labels.length;i++){
                    JSONObject row=rows.getJSONObject(i);labels[i]=row.getString("name");
                    if(row.optBoolean("active"))selected=i;
                }
                new AlertDialog.Builder(activity).setTitle("Озвучка").setSingleChoiceItems(labels,selected,(d,i)->{
                    web.evaluateJavascript(script+"("+JSONObject.quote(labels[i])+")",null);d.dismiss();
                }).setNegativeButton("Закрыть",null).show();
            }catch(Exception e){unavailable(activity);}
        });
    }
    private static void unavailable(Activity a){Toast.makeText(a,"Плеер пока не сообщил о доступных дорожках",Toast.LENGTH_SHORT).show();}
}
