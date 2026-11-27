'use strict';
// focusFirstResult: a TV search was just submitted; followFirstResult: keep the first card focused while
// more sites answer and the order changes, until the viewer moves with the arrows.
let searchDraft='', filtersOpen=false, showSiteStatus=false, focusFirstResult=false, followFirstResult=false;
const filterDefaults={kind:'', from:'', to:'', rating:'', order:'match', site:''};
let searchFilters=Object.assign({},filterDefaults);

function searchText(value) { return value.toLowerCase().replace(/ё/g,'е').replace(/[«»"“”]/g,'').replace(/\s+/g,' ').trim(); }
function searchKey(item) {
    return item.year ? [searchText(item.title),item.year,item.kind].join('|') : item.url;
}
function filterCount(filters=searchFilters) {
    return Object.keys(filterDefaults).filter(k=>filters[k]!==filterDefaults[k]).length;
}
function yearError() {
    const {from,to}=searchFilters;
    if([from,to].some(y=>y && (!/^\d{4}$/.test(y) || +y<1880 || +y>2199)))return 'Введите год от 1880 до 2199.';
    return from && to && +from>+to ? 'Первый год должен быть раньше последнего или совпадать с ним.' : '';
}
function matchesFilters(item) {
    const {kind,from,to,rating}=searchFilters;
    return (!kind || item.kind===kind) && (!from || +item.year>=+from) && (!to || (item.year && +item.year<=+to))
        && (!rating || Number(item.score)>=Number(rating));
}
function searchGroups(session) {
    const groups=new Map(), urls=new Set();
    const term=searchText(session.term);
    for(const site of session.sites) {
        site.items.forEach((item,rank)=>{
            if(urls.has(item.url) || !matchesFilters(item))return;
            urls.add(item.url);
            let key=searchKey(item),group=groups.get(key);
            if(group && group.items.some(other=>siteName(other.url)===siteName(item.url))) {
                key=item.url;group=groups.get(key);
            }
            if(!group){
                const title=searchText(item.title);
                const relevance=!term || title===term ? 0 : title.startsWith(term) ? 1 : title.includes(term) ? 2 : 3;
                group={key,items:[],relevance,rank,index:groups.size};groups.set(key,group);
            }
            group.items.push(item);group.rank=Math.min(group.rank,rank);
        });
    }
    return Array.from(groups.values()).sort((a,b)=>{
        const x=a.items[0],y=b.items[0];
        let diff=0;
        if(searchFilters.order==='score')diff=(+y.score || 0)-(+x.score || 0);
        if(searchFilters.order==='new')diff=(+y.year || 0)-(+x.year || 0);
        if(searchFilters.order==='old')diff=(+x.year || Infinity)-(+y.year || Infinity);
        return diff || a.relevance-b.relevance || a.rank-b.rank || a.index-b.index;
    });
}
function openGroup(group,cardButton) {
    if(group.items.length>1 && group.items[0].kind==='Сериал')linkSeries(group.items);
    const preferred=localStorage.getItem('preferredSite');
    const item=group.items.find(i=>new URL(i.url).origin===preferred) ||
        group.items.find(i=>new URL(i.url).origin==='https://rukino.ru') || group.items[0];
    openItem(item,item.openPage || item.url,cardButton);
}
function nextPage(site) {
    return site.pages.find(page=>!searchFilters.kind || !page.kind || page.kind===searchFilters.kind);
}
function kinopoiskPath(value) {
    const match=value.match(/^https?:\/\/(?:www\.)?kinopoisk\.ru\/(series|film)\/(\d+)\/?(?:[?#].*)?$/i);
    return match ? '/'+(match[1].toLowerCase()==='series'?'series':'movie')+'/'+match[2] : '';
}
async function readSearchPage(session,site,page) {
    if(site.busy)return;
    site.busy=true;site.error='';site.retry=page;renderSearchResults();
    try {
        const data=session.direct ? await request('detail',{url:session.direct}).then(d=>{
            if(!d.item)throw Error('Ссылка не ведёт на карточку');
            return {root:site.site,items:[Object.assign({},d.item,{openPage:d.page})],categories:{}};
        }) : session.kinopoisk ? await request('kinopoisk',{path:session.kinopoisk}) :
            !session.term && !page && home && home.root===site.site ? home :
            !page && session.term ? await titlesAt(site.site,session.term) :
            await request('list',{mirror:page ? site.resolved : site.site,page:page ? page.url : '',query:session.term});
        if(results!==session)return;
        site.resolved=data.root;
        const known=new Set(site.items.map(item=>item.url));
        for(const item of data.items)if(!known.has(item.url)){known.add(item.url);site.items.push(item);}
        if(page)site.pages=site.pages.filter(p=>p.url!==page.url);
        function enqueue(url,kind) {
            if(url && !site.visited.has(url)){site.visited.add(url);site.pages.push({url,kind});}
        }
        if(!session.term && !page) {
            enqueue(data.categories['Фильмы'],'Фильм');enqueue(data.categories['Сериалы'],'Сериал');
        }
        enqueue(data.next,page ? page.kind : '');
        site.succeeded=true;
    }catch(e){if(results===session)site.error=e.message;}
    finally{site.busy=false;if(results===session)renderSearchResults();}
}
function catalogLink(value) {
    let u;try{u=new URL(value);}catch(_){return '';}
    if(u.protocol!=='https:' || u.username || u.password || u.port)return '';
    const sites={'lordfilmy-2026.com':'gg.maxfilm3.link','gg.maxfilm3.link':'gg.maxfilm3.link','www.lordtvserials.ru':'www.lordtvserials.ru','w1.zona.im':'w1.zona.im'};
    const host=sites[u.hostname];
    if(!host || u.pathname==='/')return '';
    const episode=host==='gg.maxfilm3.link' && /^#episode-\d+$/.test(u.hash)?u.hash:'';
    return 'https://'+host+u.pathname+episode;
}
function startSearch(raw,forceExact=false) {
    if(yearError()){renderSearchResults();return;}
    query=raw.trim();searchDraft=query;
    const kinopoisk=kinopoiskPath(query), direct=catalogLink(query);
    if(query && !kinopoisk && !direct){
        const recent=readJson(localStorage.getItem(storeKey('recentSearches')),'[]');
        localStorage.setItem(storeKey('recentSearches'),JSON.stringify([query,...recent.filter(q=>q!==query)].slice(0,5)));
    }
    let sites=kinopoisk && forceExact ? ['https://rukino.ru'] : searchFilters.site ? [searchFilters.site] : searchSites();
    if(kinopoisk)sites=sites.filter(site=>site==='https://rukino.ru');
    if(direct)sites=[new URL(direct).origin];
    const session={term:query,kinopoisk,direct,sites:sites.map(site=>({site,resolved:site,items:[],pages:[],visited:new Set(),
        busy:false,succeeded:false,error:'',retry:null}))};
    results=session;
    const input=$('title-query');if(input)input.blur();
    focusFirstResult=mode==='tv';
    if(!sites.length)renderSearchResults();
    for(const site of session.sites)readSearchPage(session,site,null);
}
function renderSearchResults() {
    const out=$('search-results');
    if(!out || tab!=='search' || currentItem)return;
    const previous=out.contains(document.activeElement) ? document.activeElement.dataset.focusId : '';
    out.replaceChildren();
    const invalid=yearError();if(invalid){out.append(el('p','note',invalid));return;}
    if(!results){
        const recent=readJson(localStorage.getItem(storeKey('recentSearches')),'[]');
        if(recent.length){
            out.append(el('h3','section','Недавние запросы'));const row=el('div','wrap');out.append(row);
            recent.forEach(term=>row.append(btn(term,()=>{$('title-query').value=term;startSearch(term);},'chip')));
        }else out.append(el('p','note','Введите название или выберите фильтры и нажмите «Найти».'));
        return;
    }
    const session=results, sources=el('div','search-sites');
    if(session.kinopoisk && !session.sites.length){
        out.append(note('Поиск по ссылке Кинопоиска доступен на Rukino','Этот каталог выключен или выбран другой сайт.',()=>{
            searchFilters.site='';showSearch();startSearch(query,true);
        },'Искать на Rukino'));return;
    }
    const waiting=session.sites.filter(s=>s.busy).length;
    for(const site of session.sites) {
        const host=siteName(site.resolved);
        if(site.error){
            const retry=btn(host+' · Повторить',()=>readSearchPage(session,site,site.retry),'chip');
            retry.title=site.error;retry.setAttribute('aria-label',host+'. '+site.error+' Повторить');retry.dataset.focusId='retry:'+site.site;sources.append(retry);
        }else sources.append(el('span','site-state',host+' · '+(site.busy?'ищу…':site.items.length)));
    }
    const groups=searchGroups(session);
    const heading=el('div','bar');
    heading.append(el('h2','',session.term?'Результаты':'Подборка из каталогов'),el('span','note','Найдено: '+groups.length));
    const status=btn('Источники · '+(session.sites.length-waiting)+' из '+session.sites.length,()=>{
        showSiteStatus=!showSiteStatus;sources.hidden=!showSiteStatus;status.setAttribute('aria-expanded',String(showSiteStatus));
    },'chip');
    status.dataset.focusId='site-status';status.setAttribute('aria-expanded',String(showSiteStatus));
    sources.hidden=!showSiteStatus;heading.append(status);out.append(heading,sources);
    if(groups.length) {
        out.append(SeansUI.grid(groups.map(group=>{
            const item=group.items[0],siteLabel=group.items.length>1?'Сайтов: '+group.items.length:siteName(item.url);
            return {item,meta:meta(item)+' · '+siteLabel,focusId:group.key,open:button=>openGroup(group,button)};
        })));
    }else if(!waiting){
        if(session.sites.every(s=>s.error && !s.succeeded))out.append(note('Сайты недоступны','Можно повторить поиск на отдельном сайте или проверить зеркала в настройках.',settings,'Настройки'));
        else out.append(note('Совпадений пока нет','Измените запрос или фильтры. Если доступны следующие страницы, можно загрузить ещё.'));
    }
    if(session.sites.some(s=>!s.error && nextPage(s))){
        const more=btn('Показать ещё',()=>{
            session.sites.filter(s=>!s.error && !s.busy && nextPage(s)).forEach(s=>readSearchPage(session,s,nextPage(s)));
        },'secondary more');
        more.disabled=session.sites.every(s=>s.busy || s.error || !nextPage(s));more.dataset.focusId='more';out.append(more);
        // Load the next pages by themselves once the end of the list comes into view.
        autoLoad(more,async()=>{if(!more.disabled)more.click();});
    }
    if((focusFirstResult || followFirstResult) && groups.length){
        // On a TV the search field gives up focus on submit: land on the first result instead of nowhere.
        focusFirstResult=false;followFirstResult=true;focus(out.querySelector('.card'));
    }else if(previous){
        const match=[...out.querySelectorAll('[data-focus-id]')].find(b=>b.dataset.focusId===previous && !b.disabled);
        const target=match || out.querySelector('.card') || $('search-filter-toggle');
        target.focus({preventScroll:true});if(mode==='tv')reveal(target);
    }
}
// Speech recognised by the system (remote microphone or phone): search for it right away.
function voiceResult(text){
    text=String(text || '').trim();if(!text)return;
    if(tab!=='search' || currentItem)navigate('search');
    searchDraft=text;const input=$('title-query');if(input)input.value=text;
    startSearch(text);
}
function showSearch() {
    root.replaceChildren(el('h1','','Поиск'));
    const fields=[
        {key:'kind',label:'Тип',choices:[['','Все'],['Фильм','Фильмы'],['Сериал','Сериалы']]},
        {key:'from',label:'Год от'},{key:'to',label:'Год до'},
        {key:'rating',label:'Рейтинг от',choices:[['','Любой'],['5','5'],['6','6'],['7','7'],['8','8'],['9','9']]},
        {key:'order',label:'Порядок',choices:[['match','По совпадению'],['score','По рейтингу'],['new','Сначала новые'],['old','Сначала старые']]},
        {key:'site',label:'Где искать',choices:[['','Все сайты'],...searchSites().map(url=>[url,sourceName(url)])]}
    ];
    const form=SeansUI.search({tv:mode==='tv',draft:searchDraft,initial:{...searchFilters},open:filtersOpen,fields,count:filterCount,
        voice:Cinema.canVoiceSearch()?()=>Cinema.voiceSearch():null,
        onDraft:value=>{searchDraft=value;},onSubmit:startSearch,onToggle:open=>{filtersOpen=open;},
        onChange:(key,value)=>{
            searchFilters[key]=value;
            if(key==='site' && results)startSearch(query);else renderSearchResults();
        },
        onReset:()=>{
            const scoped=!!searchFilters.site;searchFilters={...filterDefaults};
            showSearch();if(scoped && results)startSearch(query);focus($('search-filter-toggle'));
        }});
    const out=el('section');out.id='search-results';
    root.append(form,out);renderSearchResults();
}
