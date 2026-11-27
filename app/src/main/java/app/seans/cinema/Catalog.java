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
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Catalog {
    static final String DEFAULT = "https://lordfilm.movie";
    static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)\\d{2}\\b");
    private static final Pattern EPISODE = Pattern.compile("/(\\d+)-season/(\\d+)-episode/");
    private static final Pattern SEARCH_PAGE = Pattern.compile("list_submit\\(\\s*([1-9][0-9]{0,4})\\s*\\)");
    private static final Pattern SLUG_YEAR = Pattern.compile("-((?:19|20)[0-9]{2})\\.html$");
    static String https(String raw) {
        try {
            URI u = URI.create(raw.trim());
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null ? u.toASCIIString() : "";
        } catch (RuntimeException e) { return ""; }
    }
    static String origin(String raw) {
        String safe = https(raw);
        if (safe.isEmpty()) throw new IllegalArgumentException("Введите адрес сайта с https://");
        return "https://" + URI.create(safe).getRawAuthority();
    }
    static boolean localLink(String page, String link) {
        return !https(link).isEmpty() && origin(page).equalsIgnoreCase(origin(link));
    }
    static Document fetch(String url) throws IOException {
        return fetch(url,18000);
    }
    static Document fetch(String url,int timeout) throws IOException {
        String safe = https(url);
        if (safe.isEmpty()) throw new IOException("Некорректный адрес страницы");
        Document doc = Jsoup.connect(safe).userAgent(UA).header("Accept-Language", "ru,en;q=0.7")
                .timeout(timeout).maxBodySize(4 * 1024 * 1024).get();
        if (https(doc.location()).isEmpty()) throw new IOException("Сайт переадресовал на незащищённый адрес");
        return doc;
    }
    static String text(Element root, String selector) {
        Element e = root.selectFirst(selector);
        return e == null ? "" : e.text().trim();
    }
    static String url(Element e, String... attrs) {
        if (e != null) for (String key : attrs) {
            String value = https(e.absUrl(key));
            if (!value.isEmpty()) return value;
        }
        return "";
    }
    private static String match(Pattern p, String value) {
        Matcher m = p.matcher(value);
        return m.find() ? m.group() : "";
    }
    static JSONObject list(String mirror, String page, String query) throws Exception {
        String root = origin(mirror);
        if(WebCatalog.supports(root))return WebCatalog.list(root,page,query);
        ExtraCatalog extra = ExtraCatalog.forUrl(root);
        if (extra != null) return extra.list(page, query);
        if (Rukino.ROOT.equalsIgnoreCase(root)) return Rukino.list(page, query);
        String target = page;
        if (target.isEmpty()) target = query.isEmpty() ? root + "/" : root + "/index.php?do=search&subaction=search&story=" + URLEncoder.encode(query,"UTF-8");
        if (!localLink(root, target)) throw new IOException("Адрес страницы относится к другому зеркалу");
        Document doc = fetch(target);
        JSONObject result = new JSONObject();
        if (!query.isEmpty() && doc.selectFirst("#fullsearch, #searchtable, .search-page") == null)
            throw new IOException("Сайт не вернул страницу поиска");
        JSONArray items = new JSONArray();
        Set<String> seen = new HashSet<>();
        String cards = ".grid-items__item, .th-item, .shortstory, article.short, .poster-item";
        Element container = doc.selectFirst("#dle-content, #default-content");
        Element list = doc;
        if (container != null && (!query.isEmpty() || (!page.isEmpty() && container.selectFirst(cards)!=null))) list=container;
        for (Element card : list.select(cards)) {
            Element title = card.selectFirst("a.item__title, .th-title, .poster-item__title, h2 a");
            Element a = (title != null && title.is("a[href]")) ? title : card.selectFirst("a[href]");
            String link = url(a, "href");
            if (title == null || title.text().isEmpty() || !localLink(doc.location(),link) || !seen.add(link)) continue;
            String info = text(card, ".item__year, .th-year");
            String score = text(card, ".imdb, .th-rate-imdb");
            if (!score.matches(".*\\d.*")) score = text(card, ".kp, .th-rate-kp");
            String labels = (link + " " + info + " " + text(card,".item__label, .th-series") + " " + text(card, ".item__tags")).toLowerCase(Locale.ROOT);
            boolean series = labels.contains("/series/") || labels.contains("/serial") || labels.contains("сезон") || labels.contains("сери");
            String year = match(YEAR,info + " " + text(card,".kp a"));
            Matcher slugYear = SLUG_YEAR.matcher(URI.create(link).getPath());
            if (year.isEmpty() && slugYear.find()) year = slugYear.group(1);
            String rating = score.replaceAll("[^0-9.,]","").replace(',', '.');
            if (!rating.matches("(?:[0-9](?:\\.[0-9]+)?|10(?:\\.0+)?)")) rating = "";
            items.put(new JSONObject().put("url",link).put("title",title.text())
                    .put("poster",url(card.selectFirst("img"),"data-src","src")).put("year",year)
                    .put("score",rating).put("kind",series ? "Сериал" : "Фильм"));
        }
        if (items.length() == 0 && (query.isEmpty() || doc.selectFirst("#fullsearch, .search-page, .searchtable, #searchtable, .berrors, .message-info") == null))
            throw new IOException("Каталог этого зеркала не распознан. Выберите другой адрес в настройках.");
        result.put("root",origin(doc.location())).put("items",items);
        String next = url(doc.selectFirst("a[rel=next], .pagination__btn-loader a, .pnext a, .pagination__next a, a.pagination__next, .nextprev a:last-child, .navigation a:matchesOwn(Далее|Вперёд|Вперед|›)"),"href");
        if (next.isEmpty()) {
            Element active = doc.selectFirst(".pagination .navigation span, .navigation > span, .pagination__pages > span");
            if (active != null && active.nextElementSibling() != null && active.nextElementSibling().is("a")) next = url(active.nextElementSibling(),"href");
        }
        if (!query.isEmpty()) {
            Element button = doc.selectFirst("a#nextlink[onclick], a[name=nextlink][onclick]");
            if (button != null) {
                Matcher pageNumber = SEARCH_PAGE.matcher(button.attr("onclick"));
                if (pageNumber.find()) next = origin(doc.location()) + "/index.php?do=search&subaction=search&story="
                        + URLEncoder.encode(query,"UTF-8") + "&search_start=" + pageNumber.group(1);
            }
        }
        next = next.split("#",2)[0];
        result.put("next",localLink(doc.location(),next) && !next.equals(doc.location()) ? next : "");
        if (page.isEmpty() && query.isEmpty()) result.put("mirrors",mirrorLinks(doc));
        JSONObject categories = new JSONObject();
        for (Element a : doc.select("a[href]")) {
            String path = a.attr("href").replaceFirst("^https?://[^/]+","");
            if (path.matches("/(?:film|films|filmy)/")) categories.put("Фильмы",url(a,"href"));
            if (path.matches("/(?:series|serial|serials|serialy)/")) categories.put("Сериалы",url(a,"href"));
        }
        return result.put("categories",categories);
    }

    // A catalog can announce a move or publish a link to a backup domain.
    // Keep candidates separate until they return a recognizable catalog themselves.
    private static JSONArray mirrorLinks(Document doc) {
        Set<String> found = new LinkedHashSet<>();
        for (Element a : doc.select("a[href]")) {
            String label=(a.text()+" "+a.attr("title")).toLowerCase(Locale.ROOT);
            if (!(label.contains("зеркал") || label.contains("новый адрес") || label.contains("запасной адрес"))) continue;
            String link=url(a,"href");
            if (link.isEmpty() || localLink(doc.location(),link)) continue;
            URI uri=URI.create(link);
            String host=uri.getHost().toLowerCase(Locale.ROOT);
            if (uri.getPort()!=-1 || !host.matches("(?:(?:[a-z0-9-]+)\\.)*[a-z0-9-]*lords?-?films?[a-z0-9-]*\\.[a-z]{2,16}")
                    || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal")) continue;
            found.add(origin(link));
            if (found.size()==4) break;
        }
        return new JSONArray(found);
    }

    static JSONObject details(String page) throws Exception {
        Document doc = fetch(page);
        if(WebCatalog.supports(origin(doc.location())))return WebCatalog.details(doc,page);
        ExtraCatalog extra = ExtraCatalog.forUrl(doc.location());
        if (extra != null) return extra.details(doc, page);
        if (Rukino.ROOT.equalsIgnoreCase(origin(doc.location()))) return Rukino.details(doc);
        String title = text(doc, "h1");
        if (title.isEmpty()) throw new IOException("Зеркало не вернуло карточку фильма");
        JSONObject result = new JSONObject().put("title", title).put("page",doc.location());
        String description = text(doc,".page__text .full-text, .page__text, .full-text, .f-desc, .full-desc, [itemprop=description]");
        Element meta = doc.selectFirst("meta[property=og:description], meta[name=description]");
        if (description.isEmpty() && meta != null) description = meta.attr("content");
        result.put("description",description);
        result.put("backdrop",url(doc.selectFirst("meta[property=og:image]"),"content"));
        result.put("genres",String.join(" · ",doc.select(".tags .genres a, .tags .countries a").eachText()));
        JSONArray seasons = new JSONArray(), episodes = new JSONArray(), players = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (Element a : doc.select("a.season-tab, .season-select option")) {
            String link = url(a,"href","value");
            if (!localLink(doc.location(),link) || !seen.add(link)) continue;
            seasons.put(new JSONObject().put("url",link).put("label","Сезон " + a.text().replaceAll("[^0-9]","")));
        }
        for (Element a : doc.select("a.ep-card, a.epf, .episodes-table a[href]")) {
            String link = url(a,"href");
            Matcher m = EPISODE.matcher(link);
            if (!localLink(doc.location(),link) || !m.find() || !seen.add(link)) continue;
            episodes.put(new JSONObject().put("url",link).put("label","Серия " + m.group(2)).put("season",m.group(1)));
        }
        for (Element e : doc.select("video[src], video source[src]")) {
            String link = url(e,"src");
            if (!link.isEmpty() && seen.add(link)) players.put(source("Видео",link,"direct"));
        }
        for (Element e : doc.select("iframe[data-src], iframe[src]")) {
            String link = url(e,"data-src","src");
            String lower=link.toLowerCase(Locale.ROOT);
            if (link.isEmpty() || lower.contains("youtu") || lower.contains("/trailer") || lower.contains("rutube") || !seen.add(link)) continue;
            // Only player containers and published player endpoints belong in the list.
            if (e.closest("[class*=player], [class*=video], [class*=tabs], [id*=player], [class*=play]") == null
                    && !lower.matches(".*(?:/embed/|/iframe/|/play/).*")) continue;
            players.put(source(URI.create(link).getHost(),link,"frame"));
        }
        Element widget = doc.selectFirst("video-player[data-title-id][data-publisher-id]");
        Element script = doc.selectFirst("script[src^=https://player.cdnvideohub.com/]");
        if (widget != null && script != null) {
            JSONObject p = source("CDN VideoHub",url(script,"src"),"widget");
            p.put("titleId",widget.attr("data-title-id")).put("publisher",widget.attr("data-publisher-id"))
                    .put("aggregator",widget.attr("data-aggregator"));
            Matcher episode = EPISODE.matcher(page);
            if (episode.find()) p.put("season",episode.group(1)).put("episode",episode.group(2));
            JSONArray ordered = new JSONArray().put(p);
            for (int i=0;i<players.length();i++) ordered.put(players.get(i));
            players=ordered;
        }
        return result.put("players",players).put("seasons",seasons).put("episodes",episodes);
    }

    private static JSONObject source(String label,String url,String type) throws Exception {
        return new JSONObject().put("label",label).put("url",url).put("type",type);
    }
}
