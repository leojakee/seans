'use strict';
// Older Android TV WebViews support append() before replaceChildren().
if (!Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren=function(...children) {
        this.textContent='';
        this.append(...children);
    };
}
const $ = id => document.getElementById(id);
const root = $('content'), veil = $('veil'), modal = $('modal');
const pending = new Map(), detailCache = new Map();
let serial=0, version=0, tab='home', category='Все', mirror=Cinema.mirror();
let mode=localStorage.getItem('mode') || '', dialog='', priorFocus=null;
let home=null, results=null, query='', section=null, detail=null, selected=0, seasons=[], episodes=[];
let currentItem=null, returnScroll=0, titleButton=null, sectionPage='', activeEpisode='';
let favorites=readJson(localStorage.getItem('favorites'),'[]'), history=readJson(Cinema.history(),'[]');
function readJson(raw,fallback) { try {return JSON.parse(raw || fallback);} catch (_) {return JSON.parse(fallback);} }
function request(method,args) {
    const id=++serial;
    return new Promise((ok,no)=>{pending.set(id,{ok,no});Cinema.request(id,method,JSON.stringify(args));});
}
function reply(id,error,data) {
    const cb=pending.get(id); if(!cb)return;
    pending.delete(id); if(error)cb.no(new Error(error)); else cb.ok(JSON.parse(data));
}
function el(tag,cls,text) {
    const n=document.createElement(tag); if(cls)n.className=cls; if(text)n.textContent=text; return n;
}
function btn(label,fn,cls='secondary') {const b=el('button',cls,label); b.type='button';b.onclick=fn;return b;}
function img(url,lazy=false) {
    const im=el('img'); im.alt=''; if(lazy)im.loading='lazy';
    if(url && url.startsWith('https://')) im.src=url; else im.hidden=true;
    im.onerror=()=>im.hidden=true; return im;
}
const meta=item=>[item.kind,item.year].filter(Boolean).join('  ·  ');
function focus(n) {if(n)requestAnimationFrame(()=>{if(n.isConnected)n.focus({preventScroll:true});});}
function note(title,message,action,label='Повторить') {
    const n=el('section','message'); n.append(el('h2','',title));
    if(message)n.append(el('p','',message)); if(action)n.append(btn(label,action));return n;
}
function failure(error,retry) {
    root.append(note('Не удалось загрузить',error.message,retry),btn('Настройки зеркала',settings,'subtle'));
}
function setTab(value) {
    tab=value;detail=null;currentItem=null;++version;
    SeansUI.selectTab(value);
    window.scrollTo(0,0);
}
function navigate(name) {
    setTab(name); if(name==='home')showHome();else if(name==='search')showSearch();else showLibrary();
}
function grid(items) {
    return SeansUI.grid(items.map(item=>({item,meta:meta(item),open:button=>openItem(item,item.url,button)})));
}
function hero(item) {
    const b=btn('',()=>openItem(item,item.url,b),'hero');
    const image=img(item.poster);
    b.append(image,el('span','eyeline','В центре внимания'),el('h1','',item.title),el('span','meta',meta(item)));
    getDetail(item.url).then(d=>{if(b.isConnected && d.backdrop)image.src=d.backdrop;}).catch(()=>{});return b;
}
async function getDetail(url) {
    if(detailCache.has(url))return detailCache.get(url);
    const data=await request('detail',{url});
    if(detailCache.size>=40)detailCache.delete(detailCache.keys().next().value);
    detailCache.set(url,data);return data;
}
async function showHome() {
    const token=++version;
    root.replaceChildren();
    if(!home){root.append(note('Открываю каталог…',new URL(mirror).hostname));
        try {const data=await catalogWithFallback(token);if(token!==version)return;home=data;}
        catch(e){if(token===version){root.replaceChildren();failure(e,showHome);}return;}
    }
    if(token!==version)return;
    root.replaceChildren();
    if(category==='Все' && home.items[0])root.append(hero(home.items[0]));
    const bar=el('div','bar');bar.append(el('h2','','Каталог'));const chips=el('div','chips');
    for(const label of ['Все','Фильмы','Сериалы']) {
        const b=btn(label,()=>selectCategory(label),'chip'+(category===label?' active':''));b.dataset.category=label;chips.append(b);
    }
    bar.append(chips);root.append(bar);
    const data=category==='Все'? home : section;
    let items=data ? data.items : home.items.filter(i=>i.kind===(category==='Фильмы'?'Фильм':'Сериал'));
    root.append(grid(items));
    if(!items.length)root.append(note('Пока пусто','Попробуйте поиск по названию.'));
    if(data && data.next)more(data,showHome);
}
async function selectCategory(value) {
    if(value===category)return;
    category=value;section=null;
    if(value==='Все' || !home.categories[value]){await showHome();return;}
    const token=++version;root.replaceChildren(note('Загружаю '+value.toLowerCase()+'…',''));
    try{const data=await request('list',{mirror:home.root,page:home.categories[value]});
        if(token!==version)return;section=data;await showHome();
        focus([...document.querySelectorAll('[data-category]')].find(b=>b.dataset.category===value));}
    catch(e){if(token===version){root.replaceChildren();failure(e,()=>{category='Все';selectCategory(value);});}}
}
function more(data,render) {
    const b=btn('Показать ещё',async()=>{
        const token=version; b.disabled=true;b.textContent='Загружаю…';
        try{
            const next=await request('list',{mirror:data.root,page:data.next,query:tab==='search'?query:''});
            if(token!==version)return;
            const seen=new Set(data.items.map(i=>i.url));const added=next.items.filter(i=>!seen.has(i.url));
            data.items.push(...added);data.next=next.next!==data.next?next.next:'';
            await render();const first=[...root.querySelectorAll('.card')].find(x=>added[0] && x.dataset.url===added[0].url);
            if(first && mode==='tv'){first.focus();first.scrollIntoView({block:'center'});}
        }catch(e){b.disabled=false;b.textContent='Повторить загрузку';b.title=e.message;}
    },'secondary more');root.append(b);
}
function showLibrary() {
    root.replaceChildren(el('h1','','Моя коллекция'));
    const continued=history.filter(it=>!it.finished);
    if(continued.length) {
        root.append(el('h2','shelf titleline','Продолжить просмотр'),SeansUI.grid(continued.map(h=>({
            item:h.item,title:h.title,focusId:h.page,meta:h.position?'С '+clock(h.position):'Недавно открыто',
            open:button=>openItem(h.item,h.page,button)
        }))));
    }
    root.append(el('h2','shelf titleline','Избранное'));
    if(favorites.length)root.append(grid(favorites));
    else root.append(note('Здесь будут ваши фильмы','Откройте карточку и нажмите «В избранное».'));
}
function clock(ms) {const m=Math.floor(ms/60000),s=Math.floor(ms/1000)%60;return m+':'+String(s).padStart(2,'0');}
function refreshHistory(){history=readJson(Cinema.history(),'[]');if(currentItem && detail && !dialog)showDetail(mode==='tv');else if(tab==='library'&&!currentItem)showLibrary();}
function partNumber(part) {
    const match=part && part.label.match(/\d+/);
    return match ? +match[0] : 0;
}
async function openItem(item,page,from,hint=null) {
    if(!currentItem){returnScroll=window.scrollY;titleButton=from;}
    const token=++version;currentItem=item;detail=null;selected=0;activeEpisode='';sectionPage='';episodes=[];seasons=[];
    root.replaceChildren(btn('‹ Назад',back,'subtle back'),note('Открываю карточку…',item.title));window.scrollTo(0,0);
    try{
        const d=await getDetail(page);if(token!==version)return;
        detail=d;seasons=d.seasons;episodes=d.episodes;sectionPage='';activeEpisode='';
        if(!item.year && d.year)item.year=d.year;
        const seasonOf=page.match(/^(.*\/\d+-season\/)\d+-episode\//);
        if(d.activeSeason || d.activeEpisode){sectionPage=d.activeSeason || '';activeEpisode=d.activeEpisode || '';
        }else if(seasonOf){sectionPage=seasonOf[1];activeEpisode=page;
            const seasonData=await getDetail(sectionPage);if(token!==version)return;
            seasons=seasonData.seasons;episodes=seasonData.episodes;
        }else if(seasons.length){
            sectionPage=seasons[0].url;const seasonData=await getDetail(sectionPage);if(token!==version)return;episodes=seasonData.episodes;
        }
        if(hint && hint.episode){
            const season=seasons.find(s=>partNumber(s)===hint.season);
            if(season && sectionPage!==season.url){
                const seasonData=await getDetail(season.url);if(token!==version)return;
                detail=seasonData;sectionPage=season.url;episodes=seasonData.episodes;
            }
            const episode=season && episodes.find(ep=>partNumber(ep)===hint.episode);
            if(episode){
                const episodeData=await getDetail(episode.url);if(token!==version)return;
                detail=episodeData;activeEpisode=episode.url;
            }
        }
        showDetail(true);
    }catch(e){if(token===version){root.replaceChildren(btn('‹ Назад',back,'subtle back'));
        root.append(note('Не удалось открыть карточку',e.message,otherSites,'Другие источники'),btn('Повторить',()=>openItem(item,page,from,hint)));}}
}
async function selectPart(part,isSeason) {
    const token=++version;
    const status=el('p','note','Загружаю…');root.prepend(status);
    try{
        const data=await getDetail(part.url);if(token!==version)return;
        detail=data;selected=0;
        if(isSeason){sectionPage=part.url;episodes=data.episodes;activeEpisode='';}
        else activeEpisode=part.url;
        showDetail(!isSeason);
        if(isSeason)focus(root.querySelector('[data-episode]'));
    }catch(e){if(token===version)status.textContent=e.message;}
}
function showDetail(moveFocus=false) {
    const item=currentItem,d=detail;
    root.replaceChildren(btn('‹ Назад',back,'subtle back'));
    const banner=el('div','backdrop');banner.append(img(d.backdrop||item.poster));const h=el('div');
    h.append(el('div','eyeline',meta(item)),el('h1','',item.title));banner.append(h);root.append(banner);
    const body=el('div','detailbody');root.append(body);
    const actions=el('div','wrap actions');body.append(actions);
    const hasEpisodeList=seasons.length>0 || episodes.length>0;
    const selectable=!hasEpisodeList || !!activeEpisode;
    let primary;
    const series=seriesContext();
    if(selectable && (d.players.length || series)) {
        const past=history.find(x=>x.page===d.page && x.position && !x.finished);
        const website=!series && isWebsite(d.players[selected]);
        const label=website?'Открыть плеер сайта':past?'Продолжить с '+clock(past.position):'Смотреть';
        actions.append(SeansUI.playActions(label,!website,play));primary=actions.querySelector('.primary');
        if(!series && editionNote(d.players[selected]))body.append(el('p','edition-note',editionNote(d.players[selected])+' · RUTUBE'));
    }else if(selectable)body.append(el('p','section note','На этой странице не найден поддерживаемый плеер.'));
    if(d.website)actions.append(btn('Открыть страницу Zona',()=>Cinema.play(JSON.stringify(item),JSON.stringify({page:d.page,title:d.title,players:[{label:'Zona',type:'site',url:d.website}]}),0,false)));
    const chosen=series && sourcePreferences(series).choice;
    const sourceLabel=series?(chosen?'Источник · '+chosen.label+(chosen.edition?' ✂':''):'Выбрать источник'):'Источник · '+sourceName(item.url);
    const sites=btn(sourceLabel,otherSites);sites.dataset.sites='';actions.append(sites);
    if(series && !Cinema.maximumQuality()){
        const season=qualitySeason(series), saved=Cinema.savedQuality(season);
        if(saved>=-1){
            const label=saved===-1?'максимальное':saved===0?'по умолчанию':saved+'p';
            actions.append(btn('Качество · '+label,()=>{
                beginDialog('season-quality','Качество для сезона');
                modal.append(el('p','','Сохранено: '+label));
                const reset=btn('Снова спрашивать перед просмотром',()=>{
                    Cinema.forgetQuality(season);closeDialog();showDetail(true);
                },'option');
                modal.append(reset,btn('Закрыть',closeDialog,'subtle'));focus(reset);
            }));
        }
    }
    const favorite=favorites.some(x=>x.url===item.url);
    const star=btn(favorite?'✓ В избранном':'+ В избранное',()=>{
        favorites=favorite?favorites.filter(x=>x.url!==item.url):[item,...favorites];
        localStorage.setItem('favorites',JSON.stringify(favorites));showDetail();focus(root.querySelector('[data-star]'));
    });star.dataset.star='';actions.append(star);
    if(seasons.length) {
        body.append(el('h3','section','Сезоны'),SeansUI.parts(seasons,sectionPage,true,selectPart));
    }
    if(episodes.length){
        body.append(el('h3','section','Серии'),SeansUI.parts(episodes,activeEpisode,false,selectPart));
    }
    if(activeEpisode)body.append(el('p','section',d.title));
    if(!series && d.players.length>1 && selectable){
        const source=btn('Плеер · '+d.players[selected].label,()=>sourceDialog(),'subtle section');body.append(source);
    }
    if(d.genres)body.append(el('p','section',d.genres));
    if(d.description){body.append(el('h3','section','О фильме'),el('p','synopsis',d.description));}
    if(moveFocus && mode==='tv')focus(primary || body.querySelector('[data-episode]') || star);
    function play(external){if(series){playSeries(external);return;}Cinema.play(JSON.stringify(item),JSON.stringify({page:d.page,title:activeEpisode ? d.title : item.title,players:d.players}),selected,external);}
}
function sourceDialog(){
    beginDialog('sources','Источник видео');detail.players.forEach((p,i)=>modal.append(btn((i===selected?'✓ ':'')+[p.label,editionNote(p)].filter(Boolean).join(' · '),()=>{selected=i;closeDialog();showDetail(true);},'option')));
    modal.append(btn('Назад',closeDialog,'subtle'));focus(modal.querySelector('button'));
}
function beginDialog(kind,title){
    if(!dialog)priorFocus=document.activeElement;
    dialog=kind;veil.hidden=false;
    for(const n of [document.querySelector('header'),$('tabs'),root])n.inert=true;
    document.body.style.overflow='hidden';modal.replaceChildren();const h=el('h2','',title);h.id='modalTitle';modal.append(h);
}
function closeDialog(){
    if(dialog==='first')return;
    dialog='';veil.hidden=true;modal.replaceChildren();document.body.style.overflow='';
    for(const n of [document.querySelector('header'),$('tabs'),root])n.inert=false;
    focus(priorFocus);priorFocus=null;
}
function applyMode(value) {
    mode=value;localStorage.setItem('mode',value);document.body.classList.toggle('tv',value==='tv');Cinema.layout(value==='tv');
}
function modePicker(first) {
    beginDialog(first?'first':'mode','Как будете смотреть?');
    modal.append(SeansUI.modes(choose));if(!first)modal.append(btn('Назад',settings,'subtle'));
    const preferred=mode==='tv'||(!mode && Cinema.isTelevision())?'tv':'mobile';
    focus(modal.querySelector('[data-mode="'+preferred+'"]'));
    function choose(v){applyMode(v);dialog='mode';closeDialog();if(first){navigate('home');focus(document.querySelector('[data-tab=home]'));}else settings();}
}
function back() {
    if(dialog==='first')return false;
    if(dialog==='torrent-list'){otherSites();return true;}
    if(dialog==='mode'){settings();return true;}
    if(dialog==='manage-sites'){finishSiteSettings();return true;}
    if(dialog){closeDialog();return true;}
    if(currentItem){
        const url=currentItem.url,scroll=returnScroll;currentItem=null;detail=null;navigate(tab);
        requestAnimationFrame(()=>{window.scrollTo(0,scroll);const b=[...root.querySelectorAll('.card')].find(n=>n.dataset.url===url || (titleButton && titleButton.dataset.focusId && titleButton.dataset.focusId===n.dataset.focusId));focus(b || (titleButton && titleButton.classList.contains('hero')?root.querySelector('.hero'):null) || document.querySelector('[data-tab].on'));});
        return true;
    }
    if(tab!=='home'){navigate('home');focus(document.querySelector('[data-tab=home]'));return true;}
    if(category!=='Все'){selectCategory('Все');return true;}
    return false;
}
function moveFocus(key){
    const scope=dialog?modal:document;
    const nodes=[...scope.querySelectorAll('button,input,select')].filter(n=>!n.disabled && !n.closest('[inert]') && n.getClientRects().length);
    const current=document.activeElement;if(!nodes.includes(current)){if(nodes[0])nodes[0].focus();return true;}
    const r=current.getBoundingClientRect(),vertical=key==='ArrowDown'||key==='ArrowUp',sign=(key==='ArrowRight'||key==='ArrowDown')?1:-1;
    const pos=(b,axis)=>axis?(b.top+b.bottom)/2:(b.left+b.right)/2;
    const choices=nodes.filter(n=>n!==current).map(n=>{
        const b=n.getBoundingClientRect(),dist=sign*(pos(b,vertical)-pos(r,vertical)),side=Math.abs(pos(b,!vertical)-pos(r,!vertical));
        const overlap=vertical? (b.right>r.left && b.left<r.right) : (b.bottom>r.top && b.top<r.bottom);
        return {n,dist,score:dist+side*(overlap ? 0.45 : 4)+(overlap?0:100)};
    }).filter(x=>x.dist>3).sort((a,b)=>a.score-b.score);
    if(choices.length){const next=choices[0].n;next.focus({preventScroll:true});next.scrollIntoView({block:'nearest',inline:'nearest'});}
    return true;
}
document.addEventListener('keydown',e=>{
    if(e.key==='Escape'){e.preventDefault();back();return;}
    if(!e.key.startsWith('Arrow'))return;
    if(e.target.tagName==='INPUT' && !e.target.readOnly && e.target.type!=='checkbox' && ['ArrowLeft','ArrowRight'].includes(e.key))return;
    e.preventDefault();moveFocus(e.key);
});
if(mode==='tv'||mode==='mobile'){applyMode(mode);navigate('home');if(mode==='tv')focus(document.querySelector('[data-tab=home]'));}
else modePicker(true);
