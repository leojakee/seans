package app.seans.cinema;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntConsumer;

final class VideoQuality {
    static final String MAXIMUM="maximum";
    static final int ASK=-2;
    private static final int BEST=-1;
    private final Activity activity;
    private final String season;
    private final SharedPreferences prefs;
    private AlertDialog dialog;

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("video-quality",Context.MODE_PRIVATE);
    }
    static boolean maximum(Context context) { return preferences(context).getBoolean(MAXIMUM,false); }
    static String seasonKey(String key) { return "season:"+key; }

    VideoQuality(Activity activity, JSONObject detail) {
        this.activity=activity;
        String key=detail.optString("qualitySeason");
        season=key.isEmpty()?"":seasonKey(key);
        prefs=preferences(activity);
    }
    boolean beforePlayback() { return !season.isEmpty() || maximum(activity); }

    // 0 keeps the player's default. BEST is resolved against each episode's own tracks.
    void choose(ArrayList<Integer> heights,int current,boolean manual,String defaultLabel,IntConsumer apply,Runnable cancel) {
        if(activity.isFinishing() || activity.isDestroyed() || dialog!=null)return;
        heights=new ArrayList<>(heights);
        heights.sort(Collections.reverseOrder());
        int saved=season.isEmpty()?ASK:prefs.getInt(season,ASK);
        int preferred=maximum(activity)?BEST:saved;
        int highest=heights.isEmpty()?0:heights.get(0);
        if(!manual && (heights.isEmpty() || preferred==BEST || preferred==0 || heights.contains(preferred))){
            apply.accept(heights.isEmpty()?0:preferred==BEST?highest:preferred);
            return;
        }

        ArrayList<Integer> values=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        values.add(0);labels.add(defaultLabel);
        if(highest>0){
            values.add(BEST);labels.add("Максимальное ("+highest+"p)");
            for(int h:heights){values.add(h);labels.add(h+"p");}
        }
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        ListView list=new ListView(activity);list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_list_item_single_choice,labels));
        content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        int index=values.indexOf(manual?current:preferred);
        if(index<0)index=Math.max(0,values.indexOf(current));
        list.setItemChecked(index,true);
        CheckBox remember=new CheckBox(activity);remember.setText("Запомнить для этого сезона");
        remember.setChecked(saved!=ASK);
        if(!season.isEmpty())content.addView(remember);
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle("Качество видео").setView(content);
        if(saved>0 && !heights.contains(saved) && !manual)builder.setMessage("Сохранённое качество "+saved+"p недоступно для этой серии.");
        dialog=builder.setPositiveButton(manual?"Применить":"Смотреть",(d,w)->{
            int choice=values.get(list.getCheckedItemPosition());
            if(!season.isEmpty()){
                SharedPreferences.Editor edit=prefs.edit();
                if(remember.isChecked())edit.putInt(season,choice);else edit.remove(season);
                edit.apply();
            }
            apply.accept(choice==BEST?highest:choice);
        }).setNegativeButton("Назад",(d,w)->cancel.run()).setOnCancelListener(d->cancel.run()).create();
        dialog.setOnDismissListener(d->dialog=null);
        dialog.show();
        // Bound the list on a handset in landscape and keep the checkbox and buttons reachable.
        int height=activity.getResources().getDisplayMetrics().heightPixels;
        int row=Math.round(52*activity.getResources().getDisplayMetrics().density);
        list.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.min(row*values.size(),Math.round(height*0.42f))));
        list.requestFocus();list.setSelection(index);
    }
    void close() {
        if(dialog!=null){dialog.dismiss();dialog=null;}
    }
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static TreeMap<Integer,TrackSelectionOverride> tracks(Player player) {
        TreeMap<Integer,TrackSelectionOverride> choices=new TreeMap<>();
        Map<Integer,Integer> rates=new TreeMap<>();
        for(Tracks.Group group:player.getCurrentTracks().getGroups()){
            if(group.getType()!=C.TRACK_TYPE_VIDEO)continue;
            for(int i=0;i<group.length;i++){
                Format f=group.getTrackFormat(i);
                if(f.height<=0 || !group.isTrackSupported(i))continue;
                if(choices.containsKey(f.height) && f.bitrate<=rates.get(f.height))continue;
                choices.put(f.height,new TrackSelectionOverride(group.getMediaTrackGroup(),i));
                rates.put(f.height,f.bitrate);
            }
        }
        return choices;
    }
}
