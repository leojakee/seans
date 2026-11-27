'use strict';
// Catalogue sections built from every enabled site: films, series or everything at once.
// Each site is read page by page as the viewer scrolls; titles found on several sites are merged.
const feeds=new Map();
function feedKind(category){return category==='Фильмы'?'Фильм':category==='Сериалы'?'Сериал':'';}
function feedFor(category){
    const sites=searchSites();
    let feed=feeds.get(category);
    if(!feed || feed.signature!==sites.join(' ')){
        feed={category,kind:feedKind(category),signature:sites.join(' '),listeners:new Set(),
            sites:sites.map(site=>({site,root:site,pages:[],visited:new Set(),started:false,busy:false,done:false,error:'',items:[]}))};
        feeds.set(category,feed);
    }
    return feed;
}
function feedBusy(feed){return feed.sites.some(s=>s.busy);}
function feedDone(feed){return feed.sites.every(s=>s.done);}
async function feedStep(feed,site){
    if(site.busy || site.done)return;
    site.busy=true;
    try{
        if(!site.started){
            site.started=true;
            const data=home && home.root===site.site ? home : await request('list',{mirror:site.site});
            site.root=data.root || site.site;
            const own=feed.kind && data.categories && data.categories[feed.category];
            if(own){
                // The site has a page of its own for films or series: read that instead of the mixed front page.
                const first=await request('list',{mirror:site.root,page:own});
                site.items.push(...first.items);
                if(first.next){site.visited.add(first.next);site.pages.push(first.next);}
            }else{
                site.items.push(...data.items);
                if(data.next){site.visited.add(data.next);site.pages.push(data.next);}
            }
        }else{
            const page=site.pages.shift();
            if(page){
                const data=await request('list',{mirror:site.root,page});
                site.items.push(...data.items);
                if(data.next && !site.visited.has(data.next)){site.visited.add(data.next);site.pages.push(data.next);}
            }
        }
        if(!site.pages.length)site.done=true;
    }catch(e){site.error=e.message;site.done=true;}
    finally{site.busy=false;feed.listeners.forEach(fn=>fn());}
}
// Asks every site that still has pages for its next one; resolves when all of them answered.
function feedMore(feed){
    return Promise.all(feed.sites.filter(s=>!s.busy && !s.done).map(s=>feedStep(feed,s)));
}
// Merged titles of the feed, sites interleaved so one large catalogue does not push the others away.
function feedGroups(feed){
    const groups=new Map(), order=[];
    const longest=Math.max(0,...feed.sites.map(s=>s.items.length));
    for(let i=0;i<longest;i++){
        for(const site of feed.sites){
            const item=site.items[i];
            if(!item || (feed.kind && item.kind!==feed.kind))continue;
            const key=searchKey(item);
            let group=groups.get(key);
            if(group && group.items.some(other=>siteName(other.url)===siteName(item.url)))continue;
            if(!group){group={key,items:[]};groups.set(key,group);order.push(group);}
            group.items.push(item);
        }
    }
    return order;
}
function feedEntries(groups){
    return groups.map(group=>{
        const item=group.items[0];
        const where=group.items.length>1?'Сайтов: '+group.items.length:siteName(item.url);
        return {item,focusId:group.key,meta:meta(item)+' · '+where,open:button=>openGroup(group,button)};
    });
}
// Loads the next pages when the marker comes within a screen of the viewport (touch scrolling or the remote).
function autoLoad(marker,load){
    if(!('IntersectionObserver' in window))return;
    let running=false;
    const watcher=new IntersectionObserver(async entries=>{
        if(!marker.isConnected){watcher.disconnect();return;}
        if(running || !entries.some(e=>e.isIntersecting))return;
        running=true;
        try{await load();}finally{running=false;}
        // Still in view after the load (short page): keep going.
        if(marker.isConnected){watcher.unobserve(marker);watcher.observe(marker);}
    },{rootMargin:'0px 0px 900px 0px'});
    watcher.observe(marker);
}
function showFeed(category,token){
    const feed=feedFor(category);
    const title=el('div','feed-head');title.append(el('h1','page-title',category),el('span','note feed-state',''));
    const host=SeansUI.grid([]);
    const marker=el('div','feed-marker');
    root.append(title,host,marker);
    let shown=0;
    function render(){
        if(token!==version || !host.isConnected)return;
        const groups=feedGroups(feed);
        const answered=feed.sites.filter(s=>s.started && !s.busy).length;
        title.querySelector('.feed-state').textContent='Источников: '+answered+' из '+feed.sites.length+' · Найдено: '+groups.length;
        if(groups.length!==shown){shown=groups.length;SeansUI.updateGrid(host,feedEntries(groups));}
        marker.textContent=feedDone(feed)?(groups.length?'Это всё, что нашлось.':'Ничего не нашлось. Проверьте источники в настройках.'):'Загружаю…';
        settleFocus();
    }
    feed.listeners.add(render);
    render();
    if(!feed.sites.some(s=>s.started))feedMore(feed);
    autoLoad(marker,()=>feedDone(feed)?Promise.resolve():feedMore(feed));
    // Forget the listener once the section is left.
    const cleanup=setInterval(()=>{if(!host.isConnected){feed.listeners.delete(render);clearInterval(cleanup);}},2000);
}
// A home-screen shelf filled from the feed as sites answer.
function feedShelf(category,title){
    const feed=feedFor(category);
    const host=SeansUI.grid([]);
    const block=shelf(title,host,category);
    let shown=0;
    function render(){
        if(!host.isConnected){feed.listeners.delete(render);return;}
        const groups=feedGroups(feed).slice(0,24);
        if(groups.length!==shown){shown=groups.length;SeansUI.updateGrid(host,feedEntries(groups));settleFocus();}
        block.hidden=!groups.length;
    }
    feed.listeners.add(render);
    if(!feed.sites.some(s=>s.started))feedMore(feed);
    requestAnimationFrame(render);
    return block;
}
