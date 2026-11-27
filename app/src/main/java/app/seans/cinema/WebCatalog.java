package app.seans.cinema;

import org.json.JSONArray;
import org.json.JSONObject;
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

final class WebCatalog {
    static final String MAX="https://gg.maxfilm3.link", TV="https://www.lordtvserials.ru", ZONA="https://w1.zona.im";
    private static final Pattern YEAR=Pattern.compile("\\b(?:19|20)\\d{2}\\b");
    private static final Pattern SEASON=Pattern.compile("(?:сезон\\s*(\\d+)|(\\d+)\\s*сезон)",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern TV_EP=Pattern.compile("/(\\d+)-seriya/?$");
    private static final Pattern Z_PART=Pattern.compile("/season-(\\d+)(?:/episode-(\\d+))?/?$");
    static boolean supports(String root){return MAX.equals(root)||TV.equals(root)||ZONA.equals(root);}
    private static String number(Pattern p,String value){
        Matcher m=p.matcher(value);
        if(!m.find())return "";
        for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null)return m.group(i);
        return m.group();
    }
    private static String clean(String name){
        return name.replaceFirst("(?iu)\\s*смотреть онлайн.*$","").replaceFirst("\\s*\\((?:19|20)\\d{2}\\)\\s*$","")
                .replaceFirst("(?iu)[, ]*(?:сезон\\s*\\d+|\\d+\\s*сезон)\\s*$","").trim();
    }
    static boolean titlePath(String root,String path){
        if(MAX.equals(root))return path.matches("/[0-9]+-[^/]+\\.html");
        if(TV.equals(root))return path.matches("/[a-z0-9-]+(?:/[0-9]+-seriya)?/?");
        return ZONA.equals(root) && path.matches("/(?:movies|tvseries)/[a-z0-9-]+(?:/season-[0-9]+(?:/episode-[0-9]+)?)?/?");
    }
    static JSONObject list(String root,String page,String query) throws Exception {
        String encoded=URLEncoder.encode(query,"UTF-8");
        String search=ZONA.equals(root)?"/search/"+encoded.replace("+","%20"):"/index.php?do=search&subaction=search&story="+encoded;
        String target=page.isEmpty()?root+(query.isEmpty()?"/":search):page;
        if(!localLink(root,target))throw new IOException("Страница другого каталога");
        Document doc=fetch(target);
        if(!localLink(root,doc.location()))throw new IOException("Изменился адрес каталога");
        String selector=MAX.equals(root)?".th-item":TV.equals(root)?".item-main":"a[title][href]:has(img)";
        Element content=doc.selectFirst("#dle-content");if(content==null)content=doc;
        JSONArray items=new JSONArray();Set<String> seen=new HashSet<>();
        for(Element card:content.select(selector)){
            Element a=card.is("a")?card:card.selectFirst(TV.equals(root)?"a.item-main__title":"a[href]");
            String link=url(a,"href");
            if(!localLink(root,link)||!titlePath(root,URI.create(link).getPath())||!seen.add(link))continue;
            String raw=MAX.equals(root)?text(card,".th-title"):TV.equals(root)?a.text():a.attr("title");
            Element img=card.selectFirst("img");
            String year=number(YEAR,MAX.equals(root)?text(card,".th-year"):TV.equals(root)?raw:img==null?"":img.attr("alt"));
            boolean series=TV.equals(root)||link.contains("/tvseries/")||!number(SEASON,raw).isEmpty()||card.selectFirst(".th-series")!=null;
            if(!clean(raw).isEmpty())items.put(item(link,clean(raw),year,series,url(img,"data-src","src")));
        }
        if(items.length()==0 && (query.isEmpty()||doc.selectFirst(ZONA.equals(root)?"h1":"#fullsearch,#searchtable,.search-page,.berrors")==null))
            throw new IOException("Каталог не вернул результаты");
        String next=url(doc.selectFirst("a[rel=next],.pnext a"),"href");
        return new JSONObject().put("root",root).put("items",items).put("categories",new JSONObject())
                .put("next",localLink(root,next) && !next.equals(target)?next:"");
    }
    private static String episodesLabel(JSONArray episodes,String page)throws Exception{
        for(int i=0;i<episodes.length();i++){
            JSONObject ep=episodes.getJSONObject(i);if(page.equals(ep.optString("url")))return ep.getString("label");
        }
        return "Серия";
    }
    private static JSONObject item(String link,String name,String year,boolean series,String poster)throws Exception{
        return new JSONObject().put("url",link).put("title",name).put("year",year).put("kind",series?"Сериал":"Фильм").put("poster",poster).put("score","");
    }
    private static JSONObject part(String name,String n,String link)throws Exception{
        return new JSONObject().put("url",link).put("label",name+" "+n);
    }
    private static void addVideoHub(JSONArray players,Document doc,String season,String episode)throws Exception{
        Element widget=doc.selectFirst("video-player[data-title-id][data-publisher-id]");
        String script=url(doc.selectFirst("script[src^=https://player.cdnvideohub.com/]"),"src");
        if(widget==null || script.isEmpty())return;
        JSONObject source=new JSONObject().put("url",script).put("titleId",widget.attr("data-title-id"))
                .put("publisher",widget.attr("data-publisher-id")).put("aggregator",widget.attr("data-aggregator"));
        if(!season.isEmpty())source.put("season",season);
        if(!episode.isEmpty())source.put("episode",episode);
        players.put(new JSONObject(source.toString()).put("type","widgetMenu").put("label","VideoHub · меню озвучек"));
        players.put(source.put("type","widget").put("label","CDN VideoHub"));
    }
    static JSONObject details(Document doc,String requested)throws Exception{
        String page=doc.location().split("#",2)[0],root=origin(page),path=URI.create(page).getPath();
        if(!titlePath(root,path)||doc.selectFirst("h1")==null)throw new IOException("Неизвестная карточка");
        String h1=text(doc,"h1"),title=clean(h1),year=number(YEAR,doc.title()),base=page,active="",season=number(SEASON,h1);
        JSONArray seasons=new JSONArray(),episodes=new JSONArray(),players=new JSONArray();
        boolean series=false;
        if(MAX.equals(root)){
            String ogTitle=clean(doc.select("meta[property=og:title]").attr("content"));
            if(!ogTitle.isEmpty())title=ogTitle;
            year=number(YEAR,text(doc,"[itemprop=dateCreated]"));series=!season.isEmpty();
            String latest=number(Pattern.compile("(\\d+)\\s+сери"),text(doc,".fposter .th-series"));
            String fragment=URI.create(requested).getFragment(),ep="";
            if(series && !latest.isEmpty()){
                int count=Math.min(Integer.parseInt(latest),2000);
                seasons.put(part("Сезон",season,page));
                for(int i=1;i<=count;i++){
                    String key="episode-"+i,link=page+"#"+key;
                    episodes.put(part("Серия",String.valueOf(i),link));
                    if(key.equals(fragment)){active=link;ep=String.valueOf(i);}
                }
            }
            if(fragment!=null && !fragment.isEmpty() && active.isEmpty())throw new IOException("Серия отсутствует");
            addVideoHub(players,doc,season,ep);
        }else if(TV.equals(root)){
            series=true;base=TV_EP.matcher(page).replaceFirst("");
            if(season.isEmpty() || doc.selectFirst(".playlist-episodes")==null)throw new IOException("Нет списка серий");
            seasons.put(part("Сезон",season,base));
            for(Element row:doc.select(".playlist-episodes__item")){
                Element a=row.selectFirst("a[href]");String link=a==null?page:url(a,"href");
                String ep=number(TV_EP,link);
                if(!ep.isEmpty() && link.equals(base+"/"+ep+"-seriya"))episodes.put(part("Серия",ep,link));
            }
            String ep=number(TV_EP,path);
            if(!ep.isEmpty())active=page;
            addVideoHub(players,doc,season,ep);
        }else{
            series=path.startsWith("/tvseries/");base=Z_PART.matcher(page).replaceFirst("");
            year=number(YEAR,doc.select("meta[name=video:release_date]").attr("content"));
            Set<String> seen=new HashSet<>();
            Matcher current=Z_PART.matcher(page);season=current.find()?current.group(1):"";
            if(!season.isEmpty() && current.group(2)!=null)active=page;
            for(Element a:doc.select("a[href*=/season-]")){
                String link=url(a,"href");Matcher m=Z_PART.matcher(link);
                if(!m.find() || !link.substring(0,m.start()).equals(base)||!seen.add(link))continue;
                if(m.group(2)==null)seasons.put(part("Сезон",m.group(1),link));
                else if(season.equals(m.group(1)) || season.isEmpty() && "1".equals(m.group(1)))
                    episodes.put(part("Серия",m.group(2),link));
            }
            if(doc.selectFirst("video, a[href]:matchesOwn((?i)^Смотреть (фильм|серию)$),button:matches((?i)^Смотреть (фильм|серию)$)")!=null)
                players.put(new JSONObject().put("type","site").put("label","Zona").put("url",page));
        }
        String activeSeason=TV.equals(root)||MAX.equals(root)?base:season.isEmpty()?"":base+"/season-"+season;
        String poster=url(doc.selectFirst("meta[name=ya:ovs:poster],meta[property=og:image]"),"content");
        return new JSONObject().put("page",active.isEmpty()?page:active).put("title",title+(active.isEmpty()?"":" · "+episodesLabel(episodes,active))).put("year",year)
                .put("item",item(base,title,year,series,poster)).put("backdrop",poster)
                .put("description",text(doc,".fdesc,.page__text,.item-page__text")).put("genres","")
                .put("website",ZONA.equals(root)?page:"")
                .put("catalogId",ZONA.equals(root)?doc.select("meta[name=ya:ovs:content_id]").attr("content"):"")
                .put("seasons",seasons).put("episodes",episodes).put("players",players).put("activeEpisode",active)
                .put("activeSeason",series?activeSeason:"");
    }
}
