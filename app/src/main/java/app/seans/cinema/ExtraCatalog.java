package app.seans.cinema;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.seans.cinema.Catalog.*;

enum ExtraCatalog {
    ANTENNA("https://antenna-tv.com", "/ru", "/ru/search?q=", "a:has([class$=_tileName])", "[class$=_tileName]", "[class$=_tileSub]", "/ru/(series|movie)/[0-9]+-[^/]+", "/ru/movies", "/ru/series"),
    KINONOX("https://kinonox.com", "/", "/search?q=", "article.media-card", ".media-title", ".media-meta", "/watch/(tv|movie)/[0-9]+", "/movies", "/series"),
    SERZON("https://serzon.com", "/", "/search?q=", "a.show-card", ".card-title", ".card-title", "/show/[a-zA-Z0-9_-]+", "", "/");

    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(?:19|20)[0-9]{2}(?![0-9])");
    private static final Pattern EP = Pattern.compile("/season/([0-9]+)/episode/([0-9]+)");
    private final String root, home, search, cards, titleSelector, yearSelector, films, series;
    private final Pattern entry;

    ExtraCatalog(String root,String home,String search,String cards,String title,String year,String entry,String films,String series) {
        this.root=root;this.home=home;this.search=search;this.cards=cards;titleSelector=title;yearSelector=year;
        this.entry=Pattern.compile(entry);this.films=films;this.series=series;
    }
    static ExtraCatalog forUrl(String url) {
        if (https(url).isEmpty()) return null;
        for (ExtraCatalog c : values()) if (localLink(c.root,url)) return c;
        return null;
    }
    private static String year(String value) {
        Matcher m=YEAR.matcher(value);return m.find()?m.group():"";
    }
    JSONObject list(String page,String query) throws Exception {
        String target=page.isEmpty()?root+(query.isEmpty()?home:search+URLEncoder.encode(query,"UTF-8")):page;
        if (!localLink(root,target)) throw new IOException("Страница другого каталога");
        Document doc=fetch(target);
        if (!localLink(root,doc.location()) || doc.selectFirst("main")==null || (!query.isEmpty() && !URI.create(doc.location()).getPath().endsWith("/search")))
            throw new IOException("Каталог не вернул запрошенную страницу");
        Set<String> seen=new HashSet<>();
        JSONArray items=new JSONArray();
        int exactLookups=0;
        for (Element card : doc.select("main").select(cards)) {
            String link=url(card.is("a")?card:card.selectFirst("a.media-title"),"href");
            if (!localLink(root,link) || !entry.matcher(URI.create(link).getPath()).matches() || !seen.add(link)) continue;
            String title=text(card,titleSelector), y=year(card.select(yearSelector).text());
            if (this==SERZON) {
                Matcher suffix=Pattern.compile("\\s*\\(((?:19|20)[0-9]{2})\\)$").matcher(title);
                y=suffix.find()?suffix.group(1):"";if (!y.isEmpty()) title=title.substring(0,suffix.start());
            }
            if (title.isEmpty()) continue;
            if (y.isEmpty() && exactLookups<3 && title.equalsIgnoreCase(query) && this!=ANTENNA) {
                exactLookups++;
                try {
                    Document info=fetch(link,5500);
                    y=this==SERZON?year(schema(info,"TVSeries").optString("datePublished")):kinoYear(info);
                } catch (IOException ignored) { }
            }
            boolean show=this==SERZON || URI.create(link).getPath().matches(".*/(?:series|tv)/.*");
            String rating=text(card,this==ANTENNA?"[title=Кинопоиск]":".rating-badge").replace(',','.');
            items.put(new JSONObject().put("url",link).put("title",title).put("year",y).put("kind",show?"Сериал":"Фильм")
                    .put("poster",url(card.selectFirst("img"),"src")).put("score",rating.matches("[0-9]+(?:\\.[0-9]+)?")?rating:""));
        }
        if (items.length()==0 && query.isEmpty()) throw new IOException("На странице нет карточек каталога");
        String next=url(doc.selectFirst("a[rel=next], a.page-next, nav.pagination a:matchesOwn(Далее|Вперёд)"),"href");
        JSONObject categories=new JSONObject().put("Сериалы",root+series);
        if (!films.isEmpty()) categories.put("Фильмы",root+films);
        return new JSONObject().put("root",root).put("items",items).put("categories",categories)
                .put("next",localLink(root,next) && !target.equals(next)?next:"");
    }

    static JSONObject schema(Document doc,String type) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JSONObject main=new JSONObject(script.data());
                JSONArray graph=main.optJSONArray("@graph");
                if (graph==null) graph=new JSONArray().put(main);
                for (int i=0;i<graph.length();i++) {
                    JSONObject obj=graph.optJSONObject(i);
                    if (obj!=null && type.equals(obj.optString("@type"))) return obj;
                }
            } catch (org.json.JSONException ignored) { }
        }
        return new JSONObject();
    }
    private static String kinoYear(Document doc) {
        Element year=doc.selectFirst("[data-year]");
        if (year!=null && year.attr("data-year").matches("[0-9]{4}")) return year.attr("data-year");
        for (Element badge : doc.select(".watch-badges > span"))
            if (badge.text().matches("(?:19|20)[0-9]{2}")) return badge.text();
        return "";
    }
    JSONObject details(Document doc,String requested) throws Exception {
        String page=doc.location().split("#",2)[0];
        JSONObject data=new JSONObject().put("page",page).put("backdrop",url(doc.selectFirst("meta[property=og:image]"),"content"))
                .put("description","").put("genres","").put("players",new JSONArray()).put("seasons",new JSONArray()).put("episodes",new JSONArray());
        if (this==SERZON) return serzon(doc,requested,page,data);
        if (this==ANTENNA) {
            boolean series=URI.create(page).getPath().startsWith("/ru/series/");
            JSONObject info=schema(doc,series?"TVSeries":"Movie");
            if (info.optString("name").isEmpty()) throw new IOException("Антенна не вернула карточку");
            String watch=url(doc.selectFirst("a[href$=/watch]"),"href");
            if (localLink(root,watch) && watch.equals(page+"/watch"))
                data.getJSONArray("players").put(player("Антенна",watch,"site"));
            return data.put("title",info.getString("name")).put("year",year(info.optString("datePublished"))).put("description",info.optString("description"));
        }
        String title=text(doc,"#kn-film-title");
        if (title.isEmpty()) throw new IOException("Kinonox не вернул карточку");
        String active="";
        String showPath=URI.create(page).getPath().split("/season/",2)[0];
        for (Element opt : doc.select("select[data-kn-season-select] option")) {
            String link=url(opt,"value").split("#",2)[0];
            if (!localLink(root,link) || !URI.create(link).getPath().matches(Pattern.quote(showPath)+"/season/[0-9]+")) continue;
            data.getJSONArray("seasons").put(part(opt.text(),link));
            if (opt.hasAttr("selected")) active=link;
        }
        for (Element a : doc.select("a.episode-still")) {
            String link=url(a,"href").split("#",2)[0];
            if (!localLink(root,link) || !URI.create(link).getPath().startsWith(showPath+"/season/")) continue;
            Matcher m=EP.matcher(URI.create(link).getPath());
            if (m.find()) data.getJSONArray("episodes").put(part("Серия "+m.group(2),link));
        }
        Matcher selected=EP.matcher(URI.create(page).getPath());
        boolean hasEpisode=selected.find();
        // The first provider opens a whole series even on an individual episode page.
        org.jsoup.select.Elements panels=doc.select("[data-player-url]");
        panels.sort((a,b)->Boolean.compare(!"1".equals(a.attr("data-player-provider-id")),!"1".equals(b.attr("data-player-provider-id"))));
        for (Element panel : panels) {
            String link=url(panel,"data-player-url"), tab=panel.attr("data-player-panel");
            if (!localLink(root,link) || !"/api/player_embed.php".equals(URI.create(link).getPath()) || !tab.matches("player[0-9]+")
                    || (hasEpisode && tab.equals("player1"))) continue;
            boolean vibix="3".equals(panel.attr("data-player-provider-id"));
            data.getJSONArray("players").put(player(vibix?"Vibix":text(doc,"[data-player-tab="+tab+"]"),link,vibix?"kinonoxWeb":"kinonox"));
        }
        if (hasEpisode) title+=" · "+selected.group(1)+" сезон, "+selected.group(2)+" серия";
        return data.put("title",title).put("year",kinoYear(doc)).put("activeSeason",active).put("activeEpisode",hasEpisode?page:"")
                .put("description",text(doc,".watch-description")).put("genres",String.join(" · ",doc.select(".watch-genre-link").eachText()));
    }
    static String kinonoxFrame(String api) throws Exception {
        if (!localLink(KINONOX.root,api) || !"/api/player_embed.php".equals(URI.create(api).getPath())) throw new IOException("Неизвестный адрес плеера");
        org.jsoup.Connection.Response response=Jsoup.connect(api).userAgent(UA).timeout(10000).maxBodySize(16000).ignoreContentType(true).execute();
        if (!localLink(KINONOX.root,response.url().toString())) throw new IOException("Площадка перенаправила запрос");
        JSONObject data=new JSONObject(response.body());
        String frame=url(Jsoup.parseBodyFragment(data.optString("html"),api).selectFirst("iframe[src]"),"src");
        if (!data.optBoolean("success") || frame.isEmpty()) throw new IOException("Площадка не вернула плеер");
        return frame;
    }

    private static JSONArray playlist(Document doc,String name) throws Exception {
        String prefix="window._"+name+" = ";
        for (Element script : doc.select("script:not([src])")) {
            String js=script.data();int pos=js.indexOf(prefix);
            if (pos>=0) return new JSONArray(new org.json.JSONTokener(js.substring(pos+prefix.length())));
        }
        return new JSONArray();
    }
    private static JSONObject serzon(Document doc,String requested,String page,JSONObject data) throws Exception {
        JSONObject info=schema(doc,"TVSeries");
        String title=info.optString("name");
        if (title.isEmpty()) throw new IOException("Зона сериалов не вернула карточку");
        JSONArray seasons=playlist(doc,"seasons"), episodes=playlist(doc,"episodes");
        int season=1;
        for (int i=0;i<seasons.length();i++) {
            JSONObject s=seasons.getJSONObject(i);String slug=s.optString("slug");
            if (!slug.matches("[a-zA-Z0-9_-]+")) continue;
            String link=SERZON.root+"/show/"+slug;
            Matcher number=Pattern.compile("([0-9]+)\\s*сезон",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(s.optString("name"));
            int n=number.find()?Integer.parseInt(number.group(1)):i+1;
            data.getJSONArray("seasons").put(part("Сезон "+n,link));
            if (s.optBoolean("active") || link.equals(page)) season=n;
        }
        String fragment=URI.create(requested).getFragment();boolean matched=false;
        for (int i=0;i<episodes.length();i++) {
            JSONObject e=episodes.getJSONObject(i);String url=https(e.optString("url"));
            if (url.isEmpty()) continue;
            String key="episode-"+(i+1),link=page+"#"+key;
            String name=e.optString("name").trim();
            data.getJSONArray("episodes").put(part(name.isEmpty()?"Серия "+(i+1):name,link));
            if (key.equals(fragment)) {
                matched=true;data.put("page",link).put("activeEpisode",link);
                title+=" · "+season+" сезон, "+(name.isEmpty()?"серия "+(i+1):name);
                data.getJSONArray("players").put(player("Зона сериалов",url,"direct"));
            }
        }
        if (fragment!=null && !matched) throw new IOException("Зона сериалов больше не показывает эту серию");
        return data.put("title",title).put("year",year(info.optString("datePublished"))).put("description",info.optString("description")).put("genres",info.optString("genre")).put("activeSeason",page);
    }
    private static JSONObject player(String label,String url,String type) throws Exception {
        return new JSONObject().put("label",label).put("url",url).put("type",type);
    }
    private static JSONObject part(String label,String url) throws Exception {
        return new JSONObject().put("label",label).put("url",url);
    }
}
