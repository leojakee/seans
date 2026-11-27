package app.seans.cinema;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlaybackSources {
    private static final Pattern PART = Pattern.compile("\\((\\d+) сезон\\)\\s*[-–]\\s*(\\d+) серия", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MAGNET = Pattern.compile("(?i)^magnet:\\?xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})(?:&[^\\s\\p{Cntrl}#]*)?$");
    private static final String KINONOX = "https://kinonox.com";

    private static JSONObject webPlayer(String name, String url) throws Exception {
        return new JSONObject().put("label",name).put("type","site").put("url",url);
    }

    static JSONObject collaps(JSONObject args) throws Exception {
        String id=args.optString("id");
        if (!id.matches("[1-9][0-9]{0,9}")) throw new IOException("Неизвестный ID Кинопоиска");
        int season=args.optInt("season"), episode=args.optInt("episode");
        String link="https://api.embf.net/embed/kp/"+id;
        if (episode>0 && season>0) link+="?season="+season+"&episode="+episode;
        Document doc=Catalog.fetch(link,9000);
        if (!Catalog.localLink(link,doc.location()) || doc.selectFirst("script[src*=player-venom]")==null)
            return new JSONObject();
        if (episode>0) {
            Matcher part=PART.matcher(doc.title());
            if (!part.find() || !Integer.toString(season).equals(part.group(1)) || !Integer.toString(episode).equals(part.group(2)))
                return new JSONObject();
        }
        return webPlayer("Collaps",link);
    }

    static JSONObject vibix(JSONObject args) throws Exception {
        String link=Catalog.https(args.optString("page"));
        if (!Catalog.localLink(KINONOX,link) || !URI.create(link).getPath().matches("/watch/(?:movie|tv)/[0-9]+"))
            throw new IOException("Неизвестная карточка Kinonox");
        int season=args.optInt("season"), episode=args.optInt("episode");
        String page=link;
        if (episode>0 && season>0) page+="/season/"+season+"/episode/"+episode;
        Document doc=Catalog.fetch(page,9000);
        if (!Catalog.localLink(KINONOX,doc.location()) || !URI.create(page).getPath().equals(URI.create(doc.location()).getPath()))
            return new JSONObject();
        Element panel=doc.selectFirst("[data-player-provider-id=3][data-player-url]");
        if (panel==null) return new JSONObject();
        String iframe=ExtraCatalog.kinonoxFrame(Catalog.url(panel,"data-player-url"));
        if (!isVibixFrame(iframe)) return new JSONObject();
        Document player=Catalog.fetch(iframe,9000);
        Element video=player.selectFirst("ins[data-publisher-id][data-id]");
        if (video==null || (episode>0 && (!Integer.toString(season).equals(video.attr("data-season"))
                || !Integer.toString(episode).equals(video.attr("data-episodes"))))) return new JSONObject();
        return webPlayer("Vibix",iframe);
    }

    static boolean isVibixFrame(String url) {
        return Catalog.localLink(KINONOX,url) && "/vibix_embed.php".equals(URI.create(url).getPath());
    }

    static boolean validMagnet(String s) {
        return s!=null && s.length()<=8192 && MAGNET.matcher(s).matches();
    }

    static JSONObject torrents(JSONObject args) throws Exception {
        String title=args.getString("title").trim(), year=args.optString("year");
        if (title.isEmpty() || title.length()>320) throw new IOException("Уточните название для поиска раздач");
        String link="https://jacred.su/api/v1.0/torrents?exact=true&search="+URLEncoder.encode(title,"UTF-8");
        if (year.matches("(?:19|20)[0-9]{2}")) link+="&relased="+year;
        org.jsoup.Connection.Response response=Jsoup.connect(link).userAgent(Catalog.UA).timeout(12000)
                .ignoreContentType(true).maxBodySize(4*1024*1024).execute();
        if (!Catalog.localLink(link,response.url().toString())) throw new IOException("Сервис JacRed сменил адрес");
        JSONArray raw=new JSONArray(response.body()), rows=new JSONArray();
        ArrayList<JSONObject> releases=new ArrayList<>();
        boolean series="Сериал".equals(args.optString("kind"));
        int season=args.optInt("season");
        for (int i=0;i<raw.length();i++) {
            JSONObject release=raw.optJSONObject(i);
            if (release==null || !validMagnet(release.optString("magnet")) || release.optString("title").isEmpty()) continue;
            int releaseYear=release.optInt("relased");
            if (releaseYear>0 && !year.isEmpty() && !year.equals(Integer.toString(releaseYear))) continue;
            JSONArray types=release.optJSONArray("types"), seasons=release.optJSONArray("seasons");
            if (types!=null && contains(types,series?"movie":"serial") && !contains(types,series?"serial":"movie")) continue;
            if (series && season>0 && seasons!=null && seasons.length()>0 && !contains(seasons,Integer.toString(season))) continue;
            releases.add(release);
        }
        releases.sort((a,b)->Integer.compare(b.optInt("sid"),a.optInt("sid")));
        Set<String> hashes=new HashSet<>();
        for (JSONObject release : releases) {
            String magnet=release.getString("magnet");
            Matcher m=MAGNET.matcher(magnet);
            if (!m.matches() || !hashes.add(m.group(1).toLowerCase(Locale.ROOT))) continue;
            rows.put(new JSONObject().put("title",release.getString("title")).put("size",release.optString("sizeName"))
                    .put("seeds",Math.max(0,release.optInt("sid"))).put("tracker",release.optString("tracker")).put("magnet",magnet));
        }
        return new JSONObject().put("items",rows);
    }
    private static boolean contains(JSONArray values,String value) {
        for (int i=0;i<values.length();i++) if (value.equals(values.optString(i))) return true;
        return false;
    }
}
