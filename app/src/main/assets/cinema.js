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
let favorites=readJson(localStorage.getItem('favorites'),'[]'), viewings=readJson(Cinema.history(),'[]');
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
function reveal(n) {
    const r=n.getBoundingClientRect(), top=mode==='tv'?24:72;
    if(r.top<top || r.bottom>window.innerHeight-16)n.scrollIntoView({block:'center',inline:'nearest'});
    else n.scrollIntoView({block:'nearest',inline:'nearest'});
}
function focus(n) {if(n)requestAnimationFrame(()=>{if(n.isConnected){n.focus({preventScroll:true});reveal(n);}});}
function note(title,message,action,label='Повторить') {
    const n=el('section','message'); n.append(el('h2','',title));
    if(message)n.append(el('p','',message)); if(action)n.append(btn(label,action));return n;
}
function failure(error,retry) {
    root.append(note('Не удалось загрузить',error.message,retry),btn('Настройки зеркала',settings,'subtle'));
}
const sectionTitles={home:'Главная',catalog:'Каталог',movies:'Фильмы',series:'Сериалы',continue:'Досмотреть',search:'Поиск',library:'Избранное'};
const categorySections={'Все':'home','Каталог':'catalog','Фильмы':'movies','Сериалы':'series'};
function currentSection(){return tab==='home'?categorySections[category]:tab;}
function drawChrome(){const s=currentSection();SeansUI.selectTab(tab,s,sectionTitles[s]);}
function setTab(value) {
    tab=value;detail=null;currentItem=null;++version;
    drawChrome();
    window.scrollTo(0,0);
}
// Side menu, as in TV media centres: opens with the menu button or the left arrow at the left edge.
let menuOpen=false, menuReturn=null;
function openMenu(){
    if(menuOpen || dialog)return;
    menuOpen=true;menuReturn=document.activeElement;SeansUI.menu(true);root.inert=true;document.body.classList.add('menu-open');
    focus(document.querySelector('#sidemenu .on') || document.querySelector('#sidemenu button'));
}
function closeMenu(restore=true){
    if(!menuOpen)return;
    menuOpen=false;SeansUI.menu(false);root.inert=false;document.body.classList.remove('menu-open');
    if(restore)focus(menuReturn && menuReturn.isConnected && !menuReturn.closest('#sidemenu')?menuReturn:entry());
    menuReturn=null;
}
function toggleMenu(){if(menuOpen)closeMenu();else openMenu();}
function entry(){
    // Where focus lands after switching sections: the first card, else the first control of the page.
    return root.querySelector('.card') || root.querySelector('button:not(.back),input') || document.querySelector('[data-tab].on');
}
function openSection(id){
    closeMenu(false);
    if(id==='settings'){settings();return;}
    if(id==='search' || id==='library' || id==='continue'){navigate(id);}
    else{
        const next=Object.keys(categorySections).find(c=>categorySections[c]===id) || 'Все';
        if(tab!=='home' || currentItem){category='Все';section=null;navigate('home');}
        if(next!=='Все')selectCategory(next);else if(category!=='Все'){category='Все';section=null;showHome();drawChrome();}
    }
    if(mode==='tv')setTimeout(()=>focus(entry()),60);
}
function navigate(name) {
    setTab(name); if(name==='home')showHome();else if(name==='search')showSearch();else if(name==='continue')showContinue();else showLibrary();
}
function grid(items) {
    return SeansUI.grid(items.map(item=>({item,meta:meta(item),open:button=>openItem(item,item.url,button)})));
}
async function getDetail(url) {
    if(detailCache.has(url)){const hit=detailCache.get(url);detailCache.delete(url);detailCache.set(url,hit);return hit;}
    const data=await request('detail',{url});
    if(detailCache.size>=40)detailCache.delete(detailCache.keys().next().value);
    detailCache.set(url,data);return data;
}
const sectionCache=new Map();
async function categoryData(name){
    // 'Каталог' is the catalogue's own first page; films and series have pages of their own on most sites.
    if(name==='Каталог' || !home.categories[name])return home;
    if(!sectionCache.has(name))sectionCache.set(name,await request('list',{mirror:home.root,page:home.categories[name]}));
    return sectionCache.get(name);
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
    if(category==='Все'){showShelves(token);return;}
    if(['Каталог','Фильмы','Сериалы'].includes(category)){showFeed(category,token);return;}
    const data=section || home;
    const kind=category==='Фильмы'?'Фильм':category==='Сериалы'?'Сериал':'';
    const items=section || category==='Каталог' ? data.items : home.items.filter(i=>i.kind===kind);
    root.append(el('h1','page-title',category));
    root.append(grid(items));
    if(!items.length)root.append(note('Пока пусто','Попробуйте поиск по названию.'));
    if(data.next && (section || category==='Каталог'))more(data,showHome);
    settleFocus();
}
function settleFocus(){
    // On a TV something must always be focused, or the remote seems dead.
    const a=document.activeElement;
    if(mode==='tv' && !dialog && !menuOpen && (!a || a===document.body || !a.isConnected))focus(entry());
}
function showShelves(token){
    const banner=updateBanner();if(banner)root.append(banner);
    const continued=viewings.filter(h=>!h.finished).slice(0,16);
    if(continued.length)root.append(shelf('Досмотреть',continueGrid(continued)));
    root.append(shelf('Новое в каталоге',grid(home.items.slice(0,20)),'Каталог'));
    root.append(feedShelf('Фильмы','Фильмы со всех источников'),feedShelf('Сериалы','Сериалы со всех источников'));
    if(favorites.length)root.append(shelf('Избранное',grid(favorites.slice(0,16))));
    settleFocus();
}
async function selectCategory(value) {
    if(value===category)return;
    category=value;section=null;drawChrome();window.scrollTo(0,0);
    if(value==='Все' || value==='Каталог' || !home || !home.categories[value]){await showHome();return;}
    const token=++version;root.replaceChildren(note('Загружаю '+value.toLowerCase()+'…',''));
    try{const data=await categoryData(value);
        if(token!==version)return;section=data;await showHome();
        if(mode==='tv')focus(entry());}
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
function shelf(title,region,moreCategory) {
    const block=el('section','shelf-block');region.classList.add('row');
    const head=el('div','shelf-head');head.append(el('h2','shelf-title',title));
    if(moreCategory)head.append(btn('Ещё',()=>selectCategory(moreCategory),'more-link'));
    block.append(head,region);return block;
}
function continueGrid(list) {
    return SeansUI.grid(list.map(h=>({
        item:h.item,title:h.title,focusId:h.page,meta:h.position?'С '+clock(h.position):'Недавно открыто',
        progress:h.duration>0?h.position/h.duration:0,open:button=>openItem(h.item,h.page,button)
    })));
}
// Local profile: everything below lives on this device only (localStorage and the app's own preferences).
function profileName(){return (localStorage.getItem('profileName') || '').trim();}
function profileCard(){
    const card=el('section','profile');
    const name=profileName() || 'Зритель';
    const avatar=el('div','avatar',name.slice(0,1).toUpperCase());
    const info=el('div','profile-info');
    const watched=viewings.length, finished=viewings.filter(v=>v.finished).length;
    const minutes=Math.round(viewings.reduce((sum,v)=>sum+(v.finished?(v.duration||v.position||0):(v.position||0)),0)/60000);
    info.append(el('h1','',name),el('p','note','Открыто: '+watched+' · Досмотрено: '+finished+' · В избранном: '+favorites.length+' · Просмотрено ≈ '+(minutes>=60?Math.floor(minutes/60)+' ч '+minutes%60+' мин':minutes+' мин')));
    const actions=el('div','wrap actions');
    actions.append(btn('Изменить имя',renameProfile),btn('Очистить историю',clearHistoryDialog));
    info.append(actions);card.append(avatar,info);return card;
}
function renameProfile(){
    beginDialog('profile','Как вас зовут?');
    const input=el('input','address');input.value=profileName();input.maxLength=40;input.setAttribute('aria-label','Имя');
    const save=btn('Сохранить',()=>{localStorage.setItem('profileName',input.value.trim());closeDialog();showLibrary();},'primary');
    input.onkeydown=e=>{if(e.key==='Enter')save.click();};
    modal.append(el('p','note','Имя хранится только на этом устройстве.'),input,save,btn('Отмена',closeDialog,'subtle'));focus(input);
}
function clearHistoryDialog(){
    beginDialog('profile','Очистить историю?');
    const yes=btn('Очистить',()=>{Cinema.clearHistory();viewings=[];closeDialog();showLibrary();},'primary');
    modal.append(el('p','note','Пропадут «Досмотреть» и сохранённые позиции просмотра. Избранное останется.'),yes,btn('Отмена',closeDialog,'subtle'));focus(yes);
}
// Updates from the GitHub releases of the app: checked at start (at most every 6 hours) and from the settings.
let updateInfo=null;
async function checkUpdate(force=false){
    const last=+localStorage.getItem('updateCheckedAt') || 0;
    if(!force && Date.now()-last<6*3600e3 && updateInfo)return updateInfo;
    updateInfo=await request('update',{});
    localStorage.setItem('updateCheckedAt',String(Date.now()));
    return updateInfo;
}
async function updateDialog(){
    beginDialog('update','Обновления');
    const status=el('p','note','Проверяю GitHub…');modal.append(status);
    const done=btn('Закрыть',settings,'subtle');
    try{
        const info=await checkUpdate(true);if(dialog!=='update')return;
        status.textContent='Установлена версия '+info.current+'. Последняя на GitHub: '+(info.latest || 'нет выпусков')+'.';
        if(info.newer){
            if(info.notes)modal.append(el('p','synopsis',info.notes.slice(0,600)));
            const go=btn('Скачать и установить '+info.latest,async()=>{
                go.disabled=true;go.textContent='Скачиваю…';
                try{await request('install',{url:info.url});go.textContent='Открываю установщик…';}
                catch(e){go.disabled=false;go.textContent='Повторить';status.textContent=e.message;}
            },'primary');
            modal.append(go);focus(go);
        }else{modal.append(el('p','note','У вас последняя версия.'));focus(done);}
    }catch(e){if(dialog==='update'){status.textContent=e.message;focus(done);}}
    modal.append(done);
}
function updateBanner(){
    if(!updateInfo || !updateInfo.newer)return null;
    const b=btn('Доступна новая версия '+updateInfo.latest+' · Обновить',updateDialog,'update-banner');return b;
}
function showContinue() {
    root.replaceChildren(el('h1','page-title','Досмотреть'));
    const continued=viewings.filter(it=>!it.finished);
    if(!continued.length){root.append(note('Пока нечего досматривать','Начните фильм или серию, и они появятся здесь с местом, где вы остановились.'));return;}
    root.append(continueGrid(continued),el('p','note section','Чтобы убрать что-то из списка, откройте карточку и нажмите «Убрать из „Досмотреть“».'));
    settleFocus();
}
function showLibrary() {
    root.replaceChildren(profileCard());
    const continued=viewings.filter(it=>!it.finished);
    if(continued.length) {
        root.append(el('h2','shelf titleline','Досмотреть'),continueGrid(continued));
    }
    root.append(el('h2','shelf titleline','Избранное'));
    if(favorites.length)root.append(grid(favorites));
    else root.append(note('Здесь будут ваши фильмы','Откройте карточку и нажмите «В избранное».'));
}
function clock(ms) {const m=Math.floor(ms/60000),s=Math.floor(ms/1000)%60;return m+':'+String(s).padStart(2,'0');}
function refreshHistory(){
    viewings=readJson(Cinema.history(),'[]');
    if(currentItem && detail && !dialog){
        // Returning from the player: keep the viewer where they were instead of jumping to the top.
        const y=window.scrollY, active=document.activeElement, episode=active && active.dataset.episode, star=active && active.hasAttribute('data-star');
        showDetail(false);window.scrollTo(0,y);
        const again=episode?[...root.querySelectorAll('[data-episode]')].find(n=>n.dataset.episode===episode):star?root.querySelector('[data-star]'):null;
        focus(again || (mode==='tv' && root.querySelector('.primary')));
    }else if(tab==='library'&&!currentItem)showLibrary();
}
function partNumber(part) {
    const match=part && part.label.match(/\d+/);
    return match ? +match[0] : 0;
}
async function openItem(item,page,from,hint=null) {
    followFirstResult=false;
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
        // Season, then episode, then voice-over: picking an episode opens the voice-over list from every source.
        else if(seriesContext())otherSites();
    }catch(e){if(token===version)status.textContent=e.message;}
}
function showDetail(moveFocus=false) {
    const item=currentItem,d=detail;
    root.replaceChildren(btn('‹ Назад',back,'subtle back'));
    // Title page: dimmed backdrop over the whole screen, poster on the left, facts and actions beside it.
    const bg=el('div','title-bg');bg.append(img(d.backdrop||item.poster));root.append(bg);
    const banner=el('div','title-head'),poster=el('div','title-poster'),h=el('div','title-info');
    poster.append(img(item.poster));
    if(item.kind==='Сериал' || seasons.length)poster.append(el('span','tag-tv','TV'));
    h.append(el('div','eyeline',meta(item)),el('h1','',item.title));
    const facts=el('div','facts');
    if(item.score)facts.append(el('span','fact rating',item.score));
    if(seasons.length)facts.append(el('span','fact','Сезонов: '+seasons.length));
    if(episodes.length)facts.append(el('span','fact','Серий: '+episodes.length));
    if(facts.childElementCount)h.append(facts);
    if(d.genres)h.append(el('p','genres',d.genres));
    banner.append(poster,h);root.append(banner);
    const body=el('div','detailbody');root.append(body);
    const actions=el('div','wrap actions');(mode==='tv'?h:body).append(actions);
    const hasEpisodeList=seasons.length>0 || episodes.length>0;
    const selectable=!hasEpisodeList || !!activeEpisode;
    let primary;
    const series=seriesContext();
    if(selectable && (d.players.length || series)) {
        const past=viewings.find(x=>x.page===d.page && x.position && !x.finished);
        const website=!series && isWebsite(d.players[selected]);
        const label=website?'Открыть плеер сайта':past?'Продолжить с '+clock(past.position):'Смотреть';
        actions.append(SeansUI.playActions(label,!website,play));primary=actions.querySelector('.primary');
        if(!series && editionNote(d.players[selected]))body.append(el('p','edition-note',editionNote(d.players[selected])+' · RUTUBE'));
    }else if(selectable)body.append(el('p','section note','На этой странице не найден поддерживаемый плеер.'));
    else if(episodes.length){
        // A series opens on its season: offer the next episode right away instead of making the viewer hunt for it.
        const urls=new Set(episodes.map(ep=>ep.url));
        const last=viewings.find(x=>urls.has(x.page));
        let next=episodes[0], verb='Смотреть';
        if(last){
            const index=episodes.findIndex(ep=>ep.url===last.page);
            if(!last.finished){next=episodes[index];verb='Продолжить';}
            else if(episodes[index+1])next=episodes[index+1];
        }
        primary=btn('▶ '+verb+' · '+next.label,()=>selectPart(next,false),'primary');
        actions.append(primary);
    }
    if(d.website)actions.append(btn('Открыть страницу Zona',()=>Cinema.play(JSON.stringify(item),JSON.stringify({page:d.page,title:d.title,players:[{label:'Zona',type:'site',url:d.website}]}),0,false)));
    const chosen=series && sourcePreferences(series).choice;
    const sourceLabel=series?(chosen?'Озвучка · '+chosen.label+(chosen.edition?' ✂':''):'Выбрать озвучку'):'Источник · '+sourceName(item.url);
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
    const resume=viewings.find(x=>!x.finished && (x.page===d.page || (x.item && x.item.url===item.url)));
    if(resume){
        const drop=btn('Убрать из «Досмотреть»',()=>{
            Cinema.forgetViewing(resume.page);viewings=viewings.filter(x=>x!==resume);showDetail();focus(root.querySelector('[data-star]'));
        });
        actions.append(drop);
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
    if(d.description){body.append(el('h3','section',seasons.length || item.kind==='Сериал'?'О сериале':'О фильме'),el('p','synopsis',d.description));}
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
    closeMenu(false);
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
    applyTiles();
}
// Tile layout chosen in the settings: posters per row (0 = automatic) and size of the captions.
function tileSettings(){const t=readJson(localStorage.getItem('tiles'),'{}');return {cols:+t.cols||0,size:['s','m','l'].includes(t.size)?t.size:'m'};}
const tileSizes={s:'Компактные',m:'Обычные',l:'Крупные'};
function tileLabel(){const t=tileSettings();return (t.cols?t.cols+' в ряду':'авто')+' · '+tileSizes[t.size].toLowerCase();}
function applyTiles(){
    const t=tileSettings(), body=document.body;
    body.classList.toggle('cols-fixed',!!t.cols);
    if(t.cols)body.style.setProperty('--cols',t.cols);else body.style.removeProperty('--cols');
    for(const s of ['s','m','l'])body.classList.toggle('tiles-'+s,t.size===s);
}
function tilesDialog(focusKey=''){
    beginDialog('tiles','Плитки');
    const t=tileSettings();
    function choose(change){localStorage.setItem('tiles',JSON.stringify({...tileSettings(),...change}));applyTiles();}
    modal.append(el('h3','section','Сколько в ряду'));
    const counts=el('div','wrap');
    const options=mode==='tv'?[0,4,5,6,7,8,9]:[0,2,3,4,5];
    for(const n of options){
        const b=btn(n?String(n):'Авто',()=>{choose({cols:n});tilesDialog('c'+n);},'chip'+(t.cols===n?' active':''));
        b.dataset.key='c'+n;b.setAttribute('aria-pressed',String(t.cols===n));counts.append(b);
    }
    modal.append(counts,el('h3','section','Размер'));
    const sizes=el('div','wrap');
    for(const s of ['s','m','l']){
        const b=btn(tileSizes[s],()=>{choose({size:s});tilesDialog('s'+s);},'chip'+(t.size===s?' active':''));
        b.dataset.key='s'+s;b.setAttribute('aria-pressed',String(t.size===s));sizes.append(b);
    }
    modal.append(sizes,btn('Готово',settings,'subtle section'));
    focus(modal.querySelector('[data-key="'+(focusKey || 'c'+t.cols)+'"]'));
    if(tab==='home' && !currentItem)showHome();else if(tab==='library' && !currentItem)showLibrary();
}
function modePicker(first) {
    beginDialog(first?'first':'mode','Как будете смотреть?');
    modal.append(SeansUI.modes(choose));if(!first)modal.append(btn('Назад',settings,'subtle'));
    const preferred=mode==='tv'||(!mode && Cinema.isTelevision())?'tv':'mobile';
    focus(modal.querySelector('[data-mode="'+preferred+'"]'));
    function choose(v){applyMode(v);dialog='mode';closeDialog();if(first){navigate('home');if(v==='tv')focus(entry());}else settings();}
}
function back() {
    if(menuOpen){closeMenu();return true;}
    if(dialog==='first')return false;
    if(dialog==='torrent-list'){otherSites();return true;}
    if(dialog==='mode'){settings();return true;}
    if(dialog==='manage-sites'){finishSiteSettings();return true;}
    if(dialog==='tiles' || dialog==='update'){settings();return true;}
    if(dialog){closeDialog();return true;}
    if(currentItem){
        const url=currentItem.url,scroll=returnScroll;currentItem=null;detail=null;navigate(tab);
        requestAnimationFrame(()=>{window.scrollTo(0,scroll);const b=[...root.querySelectorAll('.card')].find(n=>n.dataset.url===url || (titleButton && titleButton.dataset.focusId && titleButton.dataset.focusId===n.dataset.focusId));focus(b || entry());});
        return true;
    }
    if(tab!=='home'){navigate('home');focus(entry());return true;}
    if(category!=='Все'){selectCategory('Все');return true;}
    return false;
}
function moveFocus(key){
    const scope=dialog?modal:menuOpen?$('sidemenu'):document;
    const nodes=[...scope.querySelectorAll('button,input,select')].filter(n=>!n.disabled && !n.closest('[inert]') && n.getClientRects().length);
    const current=document.activeElement;if(!nodes.includes(current)){if(nodes[0])nodes[0].focus();return true;}
    const r=current.getBoundingClientRect(),vertical=key==='ArrowDown'||key==='ArrowUp',sign=(key==='ArrowRight'||key==='ArrowDown')?1:-1;
    const pos=(b,axis)=>axis?(b.top+b.bottom)/2:(b.left+b.right)/2;
    const choices=nodes.filter(n=>n!==current).map(n=>{
        const b=n.getBoundingClientRect(),dist=sign*(pos(b,vertical)-pos(r,vertical)),side=Math.abs(pos(b,!vertical)-pos(r,!vertical));
        const overlap=vertical? (b.right>r.left && b.left<r.right) : (b.bottom>r.top && b.top<r.bottom);
        return {n,dist,score:dist+side*(overlap ? 0.45 : 4)+(overlap?0:100)};
    }).filter(x=>x.dist>3).sort((a,b)=>a.score-b.score);
    if(menuOpen && key==='ArrowRight'){closeMenu();return true;}
    if(choices.length){const next=choices[0].n;next.focus({preventScroll:true});reveal(next);}
    else if(vertical && !dialog && !menuOpen)window.scrollBy({top:sign*Math.round(window.innerHeight*0.45),behavior:'smooth'});
    else if(key==='ArrowLeft' && !dialog)openMenu();
    else if(key==='ArrowRight' && menuOpen)closeMenu();
    return true;
}
// Touch: swipe right from the left part of the screen opens the menu, swipe left closes it.
let swipe=null;
document.addEventListener('touchstart',e=>{
    const t=e.touches[0];swipe=e.touches.length===1?{x:t.clientX,y:t.clientY,at:Date.now()}:null;
},{passive:true});
document.addEventListener('touchend',e=>{
    if(!swipe || dialog)return;
    const t=e.changedTouches[0],dx=t.clientX-swipe.x,dy=t.clientY-swipe.y,fast=Date.now()-swipe.at<600;
    const start=swipe.x;swipe=null;
    if(!fast || Math.abs(dx)<60 || Math.abs(dy)>Math.abs(dx)*0.7)return;
    if(!menuOpen && dx>0 && start<window.innerWidth*0.18)openMenu();
    else if(menuOpen && dx<0)closeMenu();
},{passive:true});
document.addEventListener('keydown',e=>{
    if(e.key==='Escape'){e.preventDefault();back();return;}
    if(!e.key.startsWith('Arrow'))return;
    followFirstResult=false;
    if(e.target.tagName==='INPUT' && !e.target.readOnly && e.target.type!=='checkbox' && ['ArrowLeft','ArrowRight'].includes(e.key))return;
    e.preventDefault();moveFocus(e.key);
});
if(mode==='tv'||mode==='mobile'){applyMode(mode);navigate('home');if(mode==='tv')focus(entry());}
else modePicker(true);
setTimeout(()=>checkUpdate().then(info=>{if(info.newer && tab==='home' && category==='Все' && !currentItem && !dialog)showHome();}).catch(()=>{}),4000);
