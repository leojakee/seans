package app.seans.cinema;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class History {
    static JSONArray all(Context context) {
        try { return new JSONArray(context.getSharedPreferences("library",0).getString("history","[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }
    static long position(Context context,String page) {
        JSONArray history=all(context);
        for (int i=0; i<history.length(); i++) {
            JSONObject row=history.optJSONObject(i);
            if (row != null && page.equals(row.optString("page"))) return row.optLong("position");
        }
        return 0;
    }
    static void opened(Context context,JSONObject item,String page,String title) {
        JSONArray history=all(context);
        for(int i=0;i<history.length();i++){
            JSONObject row=history.optJSONObject(i);
            if(row!=null && page.equals(row.optString("page")) && !row.optBoolean("finished")){
                save(context,item,page,title,row.optLong("position"),row.optLong("duration"),false);
                return;
            }
        }
        save(context,item,page,title,0,0,false);
    }
    /** Removes one title from "continue watching"; the saved position goes with it. */
    static void forget(Context context,String page) {
        JSONArray old=all(context), rows=new JSONArray();
        for (int i=0; i<old.length(); i++) {
            JSONObject row=old.optJSONObject(i);
            if (row != null && !page.equals(row.optString("page"))) rows.put(row);
        }
        context.getSharedPreferences("library",0).edit().putString("history",rows.toString()).apply();
    }
    static void clear(Context context) {
        context.getSharedPreferences("library",0).edit().remove("history").apply();
    }
    static void save(Context context,JSONObject item,String page,String title,long position,long duration,boolean finished) {
        try {
            JSONArray old=all(context), rows=new JSONArray();
            rows.put(new JSONObject().put("item",item).put("page",page).put("title",title)
                    .put("position",position).put("duration",Math.max(0,duration)).put("finished",finished));
            for (int i=0; i<old.length() && rows.length()<80; i++) {
                JSONObject row=old.getJSONObject(i);
                if (!page.equals(row.optString("page"))) rows.put(row);
            }
            context.getSharedPreferences("library",0).edit().putString("history",rows.toString()).apply();
        } catch (JSONException ignored) { }
    }
}
