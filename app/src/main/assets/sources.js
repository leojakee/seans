'use strict';
const bundledSites = ['https://rukino.ru','https://serzon.com','https://antenna-tv.com','https://kinonox.com','https://gg.maxfilm3.link','https://www.lordtvserials.ru','https://w1.zona.im','https://lordfilm.movie','https://lordfilm.legal','https://lordfilm.org','https://lordfilmlive.com'];
const mirrorHints = new Set();
function siteName(url) { return new URL(url).hostname; }
function knownSites() {
    const saved=readJson(localStorage.getItem('sites'),'[]');
    return [...new Set([mirror,...bundledSites,...(Array.isArray(saved)?saved:[])])];
}
function searchSites() {
    const disabled=readJson(localStorage.getItem('disabledSites'),'[]');
    return knownSites().filter(site=>!disabled.includes(site));
}
function manageSearchSites() {
    beginDialog('manage-sites','Источники поиска');
    const disabled=new Set(readJson(localStorage.getItem('disabledSites'),'[]'));
    function render(focusSite) {
        modal.querySelectorAll('button').forEach(b=>b.remove());
        const enabled=searchSites();
        knownSites().forEach(site=>{
            const on=!disabled.has(site);
            const b=btn('',()=>{
                if(on)disabled.add(site);else disabled.delete(site);
                localStorage.setItem('disabledSites',JSON.stringify([...disabled]));
                if(disabled.has(searchFilters.site))searchFilters.site='';
                render(site);
            },'option site-toggle');
            b.dataset.site=site;b.setAttribute('aria-pressed',String(on));b.disabled=on && enabled.length===1;
            b.append(el('strong','',sourceName(site)),el('span','',on?'Включён':'Выключен'));modal.append(b);
        });
        modal.append(btn('Готово',finishSiteSettings,'subtle section'));
        focus([...modal.querySelectorAll('button')].find(b=>b.dataset.site===focusSite && !b.disabled) || modal.querySelector('button:not(:disabled)'));
    }
    render('');
}
function finishSiteSettings() {
    settings();
    if(tab==='search' && !currentItem){
        showSearch();if(results)startSearch(query);
    }
}
function rememberSite(site) {
    const saved=knownSites().filter(s=>!bundledSites.includes(s) && s!==site);
    if(!bundledSites.includes(site))saved.unshift(site);
    localStorage.setItem('sites',JSON.stringify(saved.slice(0,12)));
}
function acceptCatalog(data) {
    rememberSite(data.root);
    (data.mirrors || []).forEach(url=>mirrorHints.add(url));
}
function switchSite(data) {
    acceptCatalog(data);
    Cinema.setMirror(data.root);mirror=data.root;home=data;category='Все';
}
function selectSite(data) {
    switchSite(data);results=null;query='';searchDraft='';detailCache.clear();
    searchFilters.site='';closeDialog();navigate('home');focus(entry());
}
async function catalogWithFallback(token) {
    const preferred=mirror;
    try {
        const data=await request('list',{mirror:preferred});
        if(token===version)switchSite(data);
        return data;
    } catch(originalError) {
        if(token!==version)throw originalError;
        const backups=knownSites().filter(s=>s!==preferred);
        if(!backups.length)throw originalError;
        root.replaceChildren(note('Ищу рабочее зеркало…',siteName(preferred)+' не ответил.'));
        // Resolve as soon as one backup returns cards, even if another has timed out.
        const data=await new Promise((resolve,reject)=>{
            let remaining=backups.length;
            backups.forEach(site=>request('list',{mirror:site}).then(resolve,()=>{
                if(--remaining===0)reject(originalError);
            }));
        });
        if(token===version)switchSite(data);
        return data;
    }
}
function settings() {
    beginDialog('settings','Настройки');
    modal.append(SeansUI.settings({tv:mode==='tv',maximum:Cinema.maximumQuality(),sites:searchSites().length,
        changeMode:()=>modePicker(false),changeSites:manageSearchSites,setMaximum:value=>Cinema.setMaximumQuality(value),tiles:tileLabel(),changeTiles:()=>tilesDialog()}),
        btn('Обновления'+(updateInfo && updateInfo.newer?' · доступна '+updateInfo.latest:''),updateDialog,'secondary section'),
        btn('ПоискКино · '+(Cinema.metadataEnabled()?'подключён':'подключить'),metadataSettings,'secondary section'),
        btn('Новые серии в избранном · '+(Cinema.episodeAlerts()?'уведомлять':'выключено'),()=>{Cinema.setEpisodeAlerts(!Cinema.episodeAlerts());settings();},'secondary section'),
        el('h3','section','Основной каталог'),
        el('p','note',Cinema.tmdbEnabled()?'Резервный каталог для полки «Новое». Поиск использует все включённые источники.':
            'Сайт, из которого заполняется полка «Новое в каталоге». Поиск использует все включённые источники.'));
    const input=el('input','address');input.type='url';input.setAttribute('aria-label','Адрес зеркала');input.value=mirror;
    const info=el('p','note');
    const save=btn('Проверить и сохранить',async()=>{
        let address;
        try {
            const value=input.value.trim();
            address=new URL(value.includes('://')?value:'https://'+value);
            if(address.protocol!=='https:' || address.username || address.password)throw Error();
        }catch(_){info.textContent='Введите HTTPS-адрес зеркала.';return;}
        save.disabled=true;info.textContent='Проверяю каталог…';
        try {const data=await request('list',{mirror:address.origin});if(save.isConnected)selectSite(data);}
        catch(e){if(save.isConnected)info.textContent=e.message;}
        finally{save.disabled=false;}
    },'primary');
    const checked=el('div','mirror-list'), summary=el('p','note');
    const scan=btn('Найти рабочие зеркала',async()=>{
        scan.disabled=true;checked.replaceChildren();summary.textContent='Проверяю каталоги…';
        const visited=new Set(), canonical=new Set();
        async function check(site) {
            visited.add(site);
            const row=el('div','mirror-row'),name=el('strong','',siteName(site)),status=el('span','note','Проверяю…');
            row.append(name,status);checked.append(row);
            try {
                const data=await request('list',{mirror:site});
                if(!scan.isConnected)return;
                acceptCatalog(data);
                if(canonical.has(data.root)){row.remove();return;}
                canonical.add(data.root);
                const label=data.root===mirror?'Выбрано':'Выбрать';
                const choose=btn(label,()=>selectSite(data),'chip');
                name.textContent=siteName(data.root);status.textContent='Каталог доступен';row.append(choose);
            }catch(e){if(scan.isConnected)status.textContent=e.message;}
        }
        await Promise.all(knownSites().map(check));
        if(!scan.isConnected)return;
        const announced=[...mirrorHints].filter(site=>!visited.has(site)).slice(0,4);
        await Promise.all(announced.map(check));
        if(!scan.isConnected)return;
        summary.textContent='Доступно каталогов: '+canonical.size+' из '+visited.size;
        scan.disabled=false;
    });
    const actions=el('div','wrap');actions.append(save,scan);
    modal.append(input,actions,info,summary,checked,btn('Закрыть',closeDialog,'subtle section'));
    if(Cinema.tmdbEnabled()){
        const credits=el('section','section');
        const logo=img('https://www.themoviedb.org/assets/2/v4/logos/v2/blue_short-8e7b30f73a4020692ccca9c88bafe5dcb6f8a62a4c6bc55cd9ba82bb2cd95f6c.svg');
        logo.width=86;logo.alt='TMDB';
        credits.append(el('h3','','О приложении'),logo,el('p','note','This product uses the TMDB API but is not endorsed or certified by TMDB.'));
        modal.append(credits);
    }
    focus(modal.querySelector('button'));
}
function metadataSettings(){
    beginDialog('metadata-settings','ПоискКино');
    const input=el('input','address');input.type='password';input.autocomplete='off';input.placeholder='API-ключ';
    input.setAttribute('aria-label','API-ключ ПоискКино');
    const status=el('p','note',Cinema.metadataEnabled()?'Ключ сохранён на устройстве.':'Ключ выдаёт Telegram-бот @poiskkinodev_bot.');
    const actions=el('div','wrap');
    actions.append(btn('Сохранить',()=>{
        const key=input.value.trim();
        if(!/^[A-Za-z0-9_-]{8,256}$/.test(key)){status.textContent='Проверьте формат API-ключа.';return;}
        Cinema.setMetadataKey(key);movieMetadata.clear();settings();
    },'primary'));
    if(Cinema.metadataEnabled())actions.append(btn('Отключить',()=>{Cinema.setMetadataKey('');movieMetadata.clear();settings();}));
    actions.append(btn('Назад',settings,'subtle'));
    modal.append(status,input,actions);focus(mode==='tv'?actions.querySelector('button'):input);
}

function sourceName(url) {
    const host=siteName(url);
    return ({'gg.maxfilm3.link':'MaxFilm','www.lordtvserials.ru':'LordTVSerials','w1.zona.im':'Zona','rukino.ru':'Rukino','serzon.com':'Зона сериалов','antenna-tv.com':'Антенна','kinonox.com':'Kinonox'})[host] || host;
}
const titleRequests=new Map();
function titlesAt(site,term) {
    const key=JSON.stringify([site,term]);
    if(titleRequests.has(key))return titleRequests.get(key);
    const pending=request('list',{mirror:site,query:term});
    titleRequests.set(key,pending);
    const clear=()=>{if(titleRequests.get(key)===pending)titleRequests.delete(key);};
    pending.then(()=>setTimeout(clear,60000),clear);
    return pending;
}
function otherSites(external=false,reason=''){
    external=external===true;
    const item=currentItem;if(!item)return;
    const tmdb=tmdbTitle(item.url);
    const current=new URL(item.url).origin;
    const releaseYear=item.year || (detail && detail.year) || '';
    const ctx=seriesContext();
    const movie=!ctx && item.kind!=='Сериал' && !seasons.length && !episodes.length;
    const hint=ctx?{season:ctx.season,episode:ctx.episode}:
        {season:partNumber(seasons.find(s=>s.url===sectionPage)),episode:partNumber(episodes.find(e=>e.url===activeEpisode))};
    const suggested=ctx && sourcePreferences(ctx).suggestion;
    beginDialog('other-sites',ctx?'Выберите озвучку':movie || tmdb?'Выберите источник':item.title);
    const list=el('div'),status=el('p','note section'),done=btn('Закрыть',closeDialog,'subtle');
    const players=el('div'), torrents=btn('Смотреть через торрент',()=>torrentDialog(item,releaseYear,hint),'option');
    if(ctx){
        const heading=[item.title,hint.season?'Сезон '+hint.season:'',hint.episode?'Серия '+hint.episode:''].filter(Boolean).join(' · ');
        modal.append(el('p','',heading));
        if(reason)modal.append(el('p','note',reason));
        if(external)modal.append(el('p','note','Для внешнего видеоплеера'));
        modal.append(players,list,status);
    }else if(movie || tmdb)modal.append(el('p','',item.title),players,list,status);
    else modal.append(list,status,players);
    modal.append(torrents,done);
    const found=new Set([current]), shown=new Set([item.url]);
    const checkedPlayers=new Set();
    let pendingPlayers=0,scanning=true;
    const counts=el('p','note');if(ctx || movie)status.before(counts);
    function updateCount(){
        if(!counts.isConnected)return;
        counts.textContent=pendingPlayers?'Проверяю плееры…':!scanning && !players.querySelector('[data-provider]')?'Для этого просмотра плееры не найдены.':'';
    }
    function addOption(route,option){
        const p=option.p;
        if(external && isWebsite(p))return;
        const key=JSON.stringify(route);
        if([...players.querySelectorAll('[data-route]')].some(b=>b.dataset.route===key))return;
        const previous=sameRoute(suggested,route);
        const b=btn('',()=>{
            if(ctx){launchSeries(ctx,route,option,external,true);return;}
            closeDialog();
            const data={page:detail?.page || item.url,sourcePage:option.page,title:item.title,players:[p]};
            Cinema.play(JSON.stringify(item),JSON.stringify(data),0,external);
        },'option');
        b.dataset.route=key;b.dataset.provider=p.label;
        b.append(el('strong','',p.label+(previous?' · прошлый выбор':'')));
        const catalog=route.method==='detail'?sourceName(route.base):route.method==='vibix'?'Kinonox':'';
        const quality=external?'Качество выбирается во внешнем плеере':
            isWebsite(p) && p.type!=='widgetMenu' && p.label!=='Collaps'?'Качество в меню сайта':'';
        b.append(el('span','',[catalog,isWebsite(p)?'Плеер сайта':'Внешний плеер при наличии',quality].filter(Boolean).join(' · ')));
        const note=editionNote(p);if(note)b.append(el('span','edition-note',note+' · полнота этой копии не проверена'));
        if(previous)players.prepend(b);else players.append(b);
        if(mode==='tv' && document.activeElement===done && players.querySelectorAll('[data-route]').length===1)focus(b);
    }
    function checkCatalog(candidate){
        function show(d){
            for(const p of d.players){
                if(p.type==='kinonoxWeb'){continue;} // The dedicated Vibix lookup handles this source.
                addOption(catalogRoute(candidate.url,p),{p,page:d.page});
            }
            if(movie && d.website && !d.players.some(p=>p.url===d.website)){
                const p={label:'Zona',type:'site',url:d.website};
                addOption(catalogRoute(candidate.url,p),{p,page:d.page});
            }
        }
        if(candidate.url===item.url && detail){show(detail);return;}
        pendingPlayers++;updateCount();
        (movie?getDetail(candidate.url):episodePage(ctx,candidate.url).then(page=>page?getDetail(page):null))
            .then(d=>{if(d && players.isConnected)show(d);})
            .catch(()=>{if(players.isConnected)catalogRetry(candidate);})
            .finally(()=>{pendingPlayers--;updateCount();});
    }
    function catalogRetry(candidate){
        const b=btn(sourceName(candidate.url)+' · повторить',()=>{b.remove();checkCatalog(candidate);},'chip');
        list.append(b);
    }
    function checkPlayer(provider,args){
        if(item.kind==='Сериал' && !ctx)return;
        if(ctx && (external || (ctx.activeEpisode && !(hint.season && hint.episode))))return;
        if(checkedPlayers.has(provider))return;
        checkedPlayers.add(provider);
        const placeholder=el('div','note');players.append(placeholder);
        async function load(){
            pendingPlayers++;updateCount();
            placeholder.replaceChildren(el('span','',provider+' · проверяю…'));
            try{
                const p=await request(provider.toLowerCase(),Object.assign({},args,hint));
                if(!placeholder.isConnected)return;
                if(!p.url){placeholder.remove();return;}
                if(ctx || movie){
                    placeholder.remove();
                    addOption({method:provider.toLowerCase(),base:args.id || args.page,label:provider},{p,page:detail?.page || item.url});
                    return;
                }
                const button=btn('',()=>{
                    closeDialog();
                    const data={page:detail?detail.page:item.url,title:activeEpisode && detail?detail.title:item.title,players:[p]};
                    Cinema.play(JSON.stringify(item),JSON.stringify(data),0,false);
                },'option');
                button.dataset.provider=provider;
                const part=hint.episode?' · сезон '+hint.season+', серия '+hint.episode:'';
                button.append(el('strong','',provider),el('span','','Онлайн-плеер'+part));
                placeholder.replaceChildren(button);
            }catch(e){
                if(placeholder.isConnected){
                    const retry=btn(provider+' · повторить',load,'chip');retry.title=e.message;placeholder.replaceChildren(retry);
                }
            }finally{pendingPlayers--;updateCount();}
        }
        load();
    }
    function discover(candidate){
        const u=new URL(candidate.url);
        const kp=kinopoiskId(candidate,candidate===item?detail:null);
        if(kp)checkPlayer('Collaps',{id:kp});
        if(u.origin==='https://kinonox.com')checkPlayer('Vibix',{page:candidate.url});
    }
    function row(candidate,chosen) {
        if(ctx || movie){checkCatalog(candidate);return;}
        const site=new URL(candidate.url).origin;
        const button=btn('',()=>{
            closeDialog();if(chosen)return;
            localStorage.setItem('preferredSite',site);openItem(candidate,candidate.url,titleButton,releaseYear && releaseYear===candidate.year?hint:null);
        },'option');
        button.dataset.site=site;
        button.append(el('strong','',(chosen?'✓ ':'')+sourceName(site)),el('span','',site==='https://antenna-tv.com'?'Плеер сайта':meta(candidate)));
        list.append(button);
        if(site==='https://rukino.ru'){
            const info=chosen && detail?Promise.resolve(detail):getDetail(candidate.url);
            info.then(d=>{if(button.isConnected && d.players.some(editionNote))button.append(el('span','edition-note','RUTUBE · ✂ Возможны сокращения'));}).catch(()=>{});
        }
    }
    function add(candidate) {
        const site=new URL(candidate.url).origin;
        const id=kinopoiskId(candidate,null);
        if(ctx && ctx.id && id && id!==ctx.id)return;
        const name=searchText(candidate.title);
        const titleMatches=name===searchText(item.title) || (tmdb && item.originalTitle && name===searchText(item.originalTitle));
        if(shown.has(candidate.url) || site===current || !titleMatches || candidate.kind!==item.kind ||
            (releaseYear && String(candidate.year)!==String(releaseYear)))return;
        if(ctx && releaseYear && candidate.year===releaseYear)linkSeries([item,candidate],ctx.id);
        shown.add(candidate.url);found.add(site);row(candidate,false);
        if(releaseYear && candidate.year===releaseYear)discover(candidate);
    }
    if(!tmdb){row(item,true);discover(item);}
    focus((ctx || movie?players:list).querySelector('button') || done);
    if(results)for(const site of results.sites)if(searchSites().includes(site.site))site.items.forEach(add);
    const remaining=searchSites().filter(site=>!found.has(site));
    const failed=[];
    function scan(sites) {
        let pending=sites.length;
        function refresh() {
            scanning=pending>0;updateCount();
            status.replaceChildren();
            if(pending){status.textContent='Проверяю каталоги · осталось '+pending;return;}
            if(!ctx && found.size===1)status.append(el('span','','В других проверенных каталогах совпадений нет. '));
            if(failed.length)status.append(btn('Повторить для недоступных · '+failed.length,()=>scan(failed.splice(0)),'chip'));
        }
        refresh();for(const site of sites){
            titlesAt(site,item.title).then(data=>{
                if(!list.isConnected)return null;
                data.items.forEach(add);
                if(tmdb && !found.has(site) && item.originalTitle && searchText(item.originalTitle)!==searchText(item.title))
                    return titlesAt(site,item.originalTitle).then(result=>{if(list.isConnected)result.items.forEach(add);});
                return null;
            }).catch(()=>failed.push(site))
                .then(()=>{pending--;if(list.isConnected)refresh();});
        }
    }
    scan(remaining);
}

function torrentDialog(item,year,hint){
    beginDialog('torrent-list','Раздачи · '+item.title);
    const info=el('p','note'),list=el('div'),backButton=btn('‹ Источники',otherSites,'subtle section');
    modal.append(info,list,backButton);focus(backButton);
    async function load(){
        info.textContent='Ищу раздачи в JacRed…';list.replaceChildren();
        try{
            const data=await request('torrents',{title:item.title,kind:item.kind,year,season:hint.season});
            if(!list.isConnected)return;
            const context=hint.season?' · сезон '+hint.season:'';
            info.textContent=data.items.length?'JacRed · найдено: '+data.items.length+context+
                (item.kind==='Сериал'?'. Серию можно выбрать в торрент-клиенте.':'. Откроется в торрент-клиенте.'):'Раздач для этого названия'+context+' не найдено.';
            let count=0;
            function more(){
                const slice=data.items.slice(count,count+12);
                const old=list.querySelector('[data-more]');if(old)old.remove();
                let first;
                slice.forEach(r=>{
                    const b=btn('',()=>Cinema.openTorrent(r.magnet),'option');
                    b.append(el('strong','',r.title),el('span','',[r.size,'Раздают: '+r.seeds,r.tracker].filter(Boolean).join(' · ')));
                    list.append(b);if(!first)first=b;
                });
                count+=slice.length;
                if(count<data.items.length){
                    const b=btn('Показать ещё',more,'chip section');b.dataset.more='';list.append(b);
                }
                if(mode==='tv' && (old || document.activeElement===backButton))focus(first || backButton);
            }
            more();
        }catch(e){if(list.isConnected){info.textContent=e.message;list.append(btn('Повторить',load));}}
    }
    load();
}
