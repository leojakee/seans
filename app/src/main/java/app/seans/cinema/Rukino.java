package app.seans.cinema;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.seans.cinema.Catalog.localLink;
import static app.seans.cinema.Catalog.text;
import static app.seans.cinema.Catalog.url;

final class Rukino {
    static final String ROOT = "https://rukino.ru";
    private static final Pattern ITEM = Pattern.compile("/(movie|series)/([0-9]+)/?");
    private static final Pattern EPISODE = Pattern.compile("/series/([0-9]+)/season_([0-9]+)/episode_([0-9]+)/?");
    private static final Pattern YEAR = Pattern.compile("\\b(?:19|20)[0-9]{2}\\b");
    private static final Pattern SCORE = Pattern.compile("(?:КП|Кинопоиск)\\s+([0-9]+(?:[.,][0-9]+)?)");

    static JSONObject list(String page, String query) throws Exception {
        String target = page;
        if (target.isEmpty()) target = query.isEmpty() ? ROOT + "/" : ROOT + "/search?q=" + URLEncoder.encode(query, "UTF-8");
        if (!localLink(ROOT, target)) throw new IOException("Адрес страницы относится к другому каталогу");
        Document doc = Catalog.fetch(target);
        if (!localLink(ROOT, doc.location()) || doc.selectFirst("form.home-search") == null
                || (!query.isEmpty() && doc.selectFirst(".search-results") == null))
            throw new IOException("Каталог Rukino не распознан");
        JSONArray items = new JSONArray();
        Set<String> seen = new HashSet<>();
        Element grid = doc.selectFirst("[data-catalog-grid]");
        Element container = grid != null ? grid : doc;
        for (Element card : container.select("article.catalog-card")) {
            String link = url(card.selectFirst("a[href]"), "href");
            String title = text(card, "h3");
            if (title.isEmpty() || !localLink(ROOT, link) || !ITEM.matcher(URI.create(link).getPath()).matches() || !seen.add(link)) continue;
            items.put(item(link, title, url(card.selectFirst("img"), "src"), text(card, "p"), text(card, ".catalog-rating")));
        }
        if (items.length() == 0 && query.isEmpty()) throw new IOException("Каталог Rukino не вернул карточки");
        String next = url(doc.selectFirst("a[rel=next]"), "href");
        return listing(items, localLink(ROOT, next) && !target.equals(next) ? next : "");
    }

    private static JSONObject listing(JSONArray items, String next) throws Exception {
        JSONObject categories = new JSONObject().put("Фильмы", ROOT + "/filter/type-movie/").put("Сериалы", ROOT + "/filter/type-tv-series/");
        return new JSONObject().put("root", ROOT).put("items", items).put("categories", categories).put("next", next);
    }

    private static JSONObject item(String link, String title, String poster, String yearText, String ratingText) throws Exception {
        Matcher year = YEAR.matcher(yearText), rating = SCORE.matcher(ratingText);
        return new JSONObject().put("url", link).put("title", title).put("poster", poster)
                .put("year", year.find() ? year.group() : "").put("score", rating.find() ? rating.group(1).replace(',', '.') : "")
                .put("kind", URI.create(link).getPath().startsWith("/series/") ? "Сериал" : "Фильм");
    }

    static JSONObject lookup(String path) throws Exception {
        if (!ITEM.matcher(path).matches()) throw new IOException("Неизвестный адрес карточки");
        String link = ROOT + path;
        JSONArray items = new JSONArray();
        try {
            Document doc = Catalog.fetch(link);
            if (!localLink(ROOT, doc.location()) || !URI.create(doc.location()).getPath().replaceFirst("/$", "").equals(path))
                return listing(items, "");
            String title = text(doc, "#movie-title");
            if (!title.isEmpty()) items.put(item(link, title, url(doc.selectFirst("meta[property=og:image]"), "content"),
                    text(doc, ".movie-facts-list a[href*=/year-]"), text(doc, ".catalog-rating")));
        } catch (HttpStatusException e) {
            if (e.getStatusCode() != 404 && e.getStatusCode() != 410) throw e;
        }
        return listing(items, "");
    }

    static JSONObject details(Document doc) throws Exception {
        String page = doc.location().split("#", 2)[0], path = URI.create(page).getPath();
        Matcher episode = EPISODE.matcher(path), entry = ITEM.matcher(path);
        boolean isEpisode = episode.matches();
        String title = text(doc, "#movie-title");
        if (title.isEmpty() || (!isEpisode && !entry.matches())) throw new IOException("Rukino не вернул карточку фильма или сериала");
        String id = isEpisode ? episode.group(1) : entry.group(2);
        JSONArray seasons = new JSONArray(), episodes = new JSONArray(), players = new JSONArray();
        String activeSeason = "";
        for (Element a : doc.select(".season-links a[href], a.episode-card[data-episode-link]")) {
            String link = url(a, "href").split("#", 2)[0];
            if (!localLink(ROOT, link)) continue;
            Matcher part = EPISODE.matcher(URI.create(link).getPath());
            if (!part.matches() || !part.group(1).equals(id)) continue;
            if (a.hasAttr("data-episode-link")) {
                episodes.put(new JSONObject().put("url", link).put("label", "Серия " + part.group(3)));
            } else {
                seasons.put(new JSONObject().put("url", link).put("label", "Сезон " + part.group(2)));
                if (a.hasAttr("aria-current")) activeSeason = link;
            }
        }
        for (Element frame : doc.select("[data-player-panel=player] iframe[data-viewer-movie]")) {
            String link = url(frame, "data-src", "src");
            if (!frame.attr("data-viewer-movie").equals(id) || !link.startsWith("https://rutube.ru/play/embed/")) continue;
            if (isEpisode && (!episode.group(2).equals(frame.attr("data-viewer-season"))
                    || !episode.group(3).equals(frame.attr("data-viewer-episode")))) continue;
            players.put(new JSONObject().put("url", link).put("type", "frame").put("label", "RUTUBE"));
        }
        return new JSONObject().put("page", page).put("title", title).put("description", text(doc, "#movie-description"))
                .put("genres", String.join(" · ", doc.select(".movie-facts-list a[href*=/genre-]").eachText()))
                .put("backdrop", url(doc.selectFirst("meta[property=og:image]"), "content"))
                .put("seasons", seasons).put("episodes", episodes).put("players", players)
                .put("activeSeason", activeSeason).put("activeEpisode", isEpisode ? page : "");
    }
}
