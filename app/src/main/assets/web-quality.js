// Runs only in the chosen provider's HTTPS origin. The channel exchanges quality choices only.
(() => {
    const channel=window.SeansVideoQuality;
    if(!channel)return;
    const hub=location.hostname==='player.cdnvideohub.com';
    const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    let stopped=false, video, root, wasMuted, nodes=new Map();
    const send=(type,data={})=>channel.postMessage(JSON.stringify(Object.assign({type},data)));
    const waitFor=async read=>{
        for(let i=0;i<60 && !stopped;i++){
            const result=read();if(result)return result;
            await delay(150);
        }
        throw Error('menu unavailable');
    };
    function height(text){
        const match=String(text).trim().match(/^(\d{2,4})p(?:\s*(?:HD|FHD|UHD))?$/i);
        return match?Number(match[1]):0;
    }
    function readOptions(list){
        const options=new Map();
        for(const el of list){
            const h=height(el.textContent);if(h)options.set(h,el);
        }
        return options.size?options:null;
    }
    function pausePreparation(){if(!stopped && video && !video.paused)video.pause();}
    function restore(){
        stopped=true;
        if(video){
            video.removeEventListener('playing',pausePreparation);
            if(wasMuted!==undefined)video.muted=wasMuted;
        }
        if(hub && root?.querySelector('[role=menu]')){
            root.querySelector('.btn[aria-label="Настройки"],.btn[title="Настройки"]')?.click();
        }
    }
    async function discover(){
        if(hub){
            root=await waitFor(()=>Array.from(document.querySelectorAll('vk-video-player, vk-video-player *'))
                .map(el=>el.shadowRoot).find(s=>s?.querySelector('video')));
            video=root.querySelector('video');
        }else{
            root=document;
            video=await waitFor(()=>root.querySelector('video'));
        }
        wasMuted=video.muted;video.muted=true;
        video.addEventListener('playing',pausePreparation);pausePreparation();
        let current=0;
        if(hub){
            const gear=()=>root.querySelector('.btn[aria-label="Настройки"],.btn[title="Настройки"]');
            if(!gear()){
                const play=await waitFor(()=>root.querySelector('.playButton'));
                play.click();
            }
            (await waitFor(gear)).click();
            const entry=await waitFor(()=>root.querySelector('[role=menuitem][aria-label^="Качество"]'));
            current=height(entry.getAttribute('aria-label').split(':').pop());
            entry.click();
            await waitFor(()=>{
                nodes=readOptions(root.querySelectorAll('[role=menuitem]')) || new Map();
                if(nodes.size)return true;
                const more=root.querySelector('[role=menuitem][aria-label="Другое"]');
                if(more && more.getClientRects().length)more.click();
                return false;
            });
        }else{
            nodes=await waitFor(()=>{
                for(const menu of root.querySelectorAll('[class*="submenu_"]')){
                    const entries=readOptions(menu.querySelectorAll('li'));
                    if(entries)return entries;
                }
                return null;
            });
            for(const [n,option] of nodes)if(/\bactive_/.test(option.className))current=n;
        }
        if(!stopped)send('options',{heights:Array.from(nodes.keys()),current});
    }
    async function apply(choice){
        if(choice>0){
            const option=nodes.get(choice);
            if(!option || !option.isConnected)throw Error('choice disappeared');
            option.click();
            if(!hub)await waitFor(()=>Array.from(document.querySelectorAll('[class*="submenu_"] li'))
                .some(el=>height(el.textContent)===choice && /\bactive_/.test(el.className)));
            else await waitFor(()=>{
                const entry=root.querySelector('[role=menuitem][aria-label^="Качество"]');
                if(entry)return height(entry.getAttribute('aria-label').split(':').pop())===choice;
                const gear=root.querySelector('.btn[aria-label="Настройки"],.btn[title="Настройки"]');
                if(gear && !root.querySelector('[role=menu]'))gear.click();
                return false;
            });
        }
        restore();
        send('applied',{height:choice});
    }
    channel.onmessage=event=>{
        const command=JSON.parse(event.data);
        if(command.action==='release'){restore();return;}
        if(command.action==='start'){
            if(video)video.play().catch(()=>{});
            return;
        }
        if(stopped)return;
        if(command.action==='prepare')discover().catch(()=>{if(!stopped)send('error');});
        if(command.action==='select')apply(Number(command.height)).catch(()=>{if(!stopped)send('error');});
    };
    send('ready');
})();
