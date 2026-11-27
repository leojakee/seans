'use strict';

// Remember how to find a player. Episode URLs and expiring stream URLs are resolved again.
function kinopoiskId(item,d) {
    const u=new URL(item.url);
    if(u.origin==='https://w1.zona.im' && d && /^\d+$/.test(d.catalogId))return d.catalogId;
    let match;
    if(u.origin==='https://rukino.ru')match=u.pathname.match(/^\/(?:movie|series)\/(\d+)\/?$/);
    if(u.origin==='https://antenna-tv.com')match=u.pathname.match(/^\/ru\/(?:movie|series)\/(\d+)-/);
    const widget=d && d.players.find(p=>p.aggregator==='kp' && /^\d+$/.test(p.titleId));
    return match?match[1]:widget?widget.titleId:'';
}
function seriesStorage(item,id) {
    if(id)return 'series-source-v1:kp:'+id;
    return localStorage.getItem('series-alias:'+item.url) || 'series-source-v1:'+item.url;
}
// Only link catalogue entries after the normal title/year match or an exact ID match.
function linkSeries(items,knownId) {
    const ids=new Set(items.map(item=>kinopoiskId(item,null)).filter(Boolean));
    if(knownId)ids.add(knownId);
    if(ids.size>1)return;
    const id=[...ids][0], keys=items.map(item=>seriesStorage(item,kinopoiskId(item,null)));
    const target=id?seriesStorage(items[0],id):keys.find(k=>localStorage.getItem(k)) || keys[0];
    for(const key of keys){
        const old=localStorage.getItem(key);
        if(key!==target && old && !localStorage.getItem(target))localStorage.setItem(target,old);
    }
    items.forEach(item=>localStorage.setItem('series-alias:'+item.url,target));
}
function seriesContext() {
    if(!currentItem || currentItem.kind!=='Сериал' || !detail)return null;
    if((seasons.length || episodes.length) && !activeEpisode)return null;
    const part=episodes.find(e=>e.url===activeEpisode);
    const season=partNumber(seasons.find(s=>s.url===sectionPage)) || Number(part && part.season) || 0;
    const episode=partNumber(part);
    const id=kinopoiskId(currentItem,detail);
    return {item:currentItem, detail, season, episode, activeEpisode,
        id,seasonKey:String(season || sectionPage || 'series'),
        episodeKey:String(episode || activeEpisode || 'series')};
}
function sourcePreferences(ctx) {
    const storage=seriesStorage(ctx.item,ctx.id);
    const saved=readJson(localStorage.getItem(storage),'{}');
    const season=(saved.seasons || {})[ctx.seasonKey] || {};
    const episode=(season.episodes || {})[ctx.episodeKey];
    return {storage,saved,season,choice:episode || season.choice,suggestion:episode || season.choice || saved.last};
}
function rememberPlayer(ctx,route,explicit) {
    const {storage,saved,season}=sourcePreferences(ctx);
    if(!saved.seasons)saved.seasons={};
    if(!season.episodes)season.episodes={};
    season.episodes[ctx.episodeKey]=route;
    if(explicit){season.choice=route;saved.last=route;}
    saved.seasons[ctx.seasonKey]=season;
    localStorage.setItem(storage,JSON.stringify(saved));
}
function isWebsite(p){return p.type==='site' || p.type==='kinonoxWeb' || p.type==='widgetMenu';}
function editionNote(p) {
    let host;
    try{host=new URL(p.url).hostname;}catch(_){return '';}
    return (host==='rutube.ru' || host.endsWith('.rutube.ru')) ? '✂ Возможны сокращения' : '';
}
function catalogRoute(base,p) {
    return {method:'detail',base,label:p.label,type:p.type,edition:editionNote(p)};
}
function sameRoute(a,b) {
    return !!a && !!b && a.method===b.method && a.base===b.base && a.label===b.label && a.type===b.type;
}
async function episodePage(ctx,base) {
    if(base===ctx.item.url)return ctx.detail.page;
    const show=await getDetail(base);
    if(!ctx.episode)return !ctx.activeEpisode && !show.episodes.length && !show.seasons.length ? show.page : '';
    let section=show;
    if(show.seasons.length){
        const season=show.seasons.find(s=>partNumber(s)===ctx.season);
        if(!season)return '';
        if(show.activeSeason!==season.url)section=await getDetail(season.url);
    }else if(ctx.season && !show.episodes.some(ep=>Number(ep.season)===ctx.season))return '';
    const ep=section.episodes.find(e=>partNumber(e)===ctx.episode && (!e.season || Number(e.season)===ctx.season));
    return ep ? ep.url : '';
}
async function resolveRoute(ctx,route) {
    if(route.method==='detail'){
        const page=await episodePage(ctx,route.base);
        if(!page)return null;
        const d=await request('detail',{url:page});
        const p=d.players.find(p=>sameRoute(route,catalogRoute(route.base,p)));
        return p && {p,page:d.page};
    }
    if(ctx.activeEpisode && !(ctx.season && ctx.episode))return null;
    const args=route.method==='collaps'?{id:route.base}:{page:route.base};
    const p=await request(route.method,Object.assign(args,{season:ctx.season,episode:ctx.episode}));
    return p.url ? {p,page:ctx.detail.page} : null;
}
function qualitySeason(ctx){return JSON.stringify([seriesStorage(ctx.item,ctx.id),ctx.seasonKey]);}
function launchSeries(ctx,route,option,external,explicit) {
    rememberPlayer(ctx,route,explicit);
    closeDialog();
    const data={page:ctx.detail.page,sourcePage:option.page,title:ctx.activeEpisode?ctx.detail.title:ctx.item.title,
        players:[option.p],seriesChoice:true,reusedSource:!explicit,
        qualitySeason:qualitySeason(ctx)};
    Cinema.play(JSON.stringify(ctx.item),JSON.stringify(data),0,external);
}
async function playSeries(external) {
    const ctx=seriesContext(), route=sourcePreferences(ctx).choice;
    if(!route){otherSites(external);return;}
    beginDialog('opening-source','Открываю '+route.label);
    const cancel=btn('Выбрать другой источник',()=>otherSites(external),'option');
    modal.append(cancel);focus(cancel);
    try{
        const option=await resolveRoute(ctx,route);
        if(!cancel.isConnected)return;
        if(option && (!external || !isWebsite(option.p))){
            launchSeries(ctx,route,option,external,false);return;
        }
    }catch(_){if(!cancel.isConnected)return;}
    otherSites(external,'Выберите замену для «'+route.label+'».');
}
