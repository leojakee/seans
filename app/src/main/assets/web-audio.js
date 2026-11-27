(choice => {
    // On touch devices the audio control uses a select. Season and episode selects sit inside a separate list.
    const audio=document.querySelector('[class*="top-left_"] > [class*="withArrow_"] > select');
    if(audio && audio.options.length){
        const tracks=Array.from(audio.options).filter(o=>!o.disabled && o.textContent.trim());
        if(choice===null)return tracks.map(o=>({name:o.textContent.trim(),active:o.selected}));
        const target=tracks.find(o=>o.textContent.trim()===choice);
        if(!target)return false;
        audio.selectedIndex=target.index;
        audio.dispatchEvent(new Event('change',{bubbles:true}));
        return true;
    }
    const menus=document.querySelectorAll('[class*="dropdown_"] [class^="menu_"]');
    for(const menu of menus){
        const items=Array.from(menu.children).filter(n=>/^item_/.test(n.className) && n.textContent.trim());
        if(!items.length || items.some(n=>/^\s*(?:\d+(?:\s|p\b|$)|(?:season|episode|сезон|серия)\s*\d)/i.test(n.textContent)))continue;
        if(choice===null)return items.map(n=>({name:n.textContent.trim(),active:/\bactive_/.test(n.className)}));
        const option=items.find(n=>n.textContent.trim()===choice);
        if(option)option.click();
        return !!option;
    }
    return [];
})
