import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {flushSync} from 'react-dom';
import createCache from '@emotion/cache';
import {CacheProvider} from '@emotion/react';
import {ThemeProvider, createTheme} from '@mui/material/styles';
import AppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Button from '@mui/material/Button';
import CardActionArea from '@mui/material/CardActionArea';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import Paper from '@mui/material/Paper';
import Switch from '@mui/material/Switch';
import FormControlLabel from '@mui/material/FormControlLabel';
import TextField from '@mui/material/TextField';
import MovieIcon from '@mui/icons-material/MovieOutlined';
import SearchIcon from '@mui/icons-material/SearchRounded';
import LibraryIcon from '@mui/icons-material/BookmarksOutlined';
import SettingsIcon from '@mui/icons-material/SettingsOutlined';
import TuneIcon from '@mui/icons-material/TuneRounded';
import TvIcon from '@mui/icons-material/TvRounded';
import PhoneIcon from '@mui/icons-material/SmartphoneRounded';
import PlayIcon from '@mui/icons-material/PlayArrowRounded';

const cache=createCache({key:'seans',nonce:document.querySelector('meta[name=style-nonce]').content,prepend:true});
const theme=createTheme({
    palette:{mode:'dark',primary:{main:'#f0cf94',contrastText:'#1d1a12'},background:{default:'#0c0d0d',paper:'#17191a'},
        text:{primary:'#f7f3e9',secondary:'#babeb5'}},
    shape:{borderRadius:14}, typography:{fontFamily:'Roboto, Arial, sans-serif',button:{textTransform:'none',fontWeight:600}},
    // A remote control always focuses "by keyboard": the focus ripple then sticks as an oval over the label.
    // Focus is drawn by screen.css instead.
    components:{MuiButton:{defaultProps:{disableElevation:true,focusRipple:false}},MuiIconButton:{defaultProps:{focusRipple:false}},
        MuiTextField:{defaultProps:{size:'small',variant:'outlined'}}}
});
function themed(content){return <CacheProvider value={cache}><ThemeProvider theme={theme}>{content}</ThemeProvider></CacheProvider>;}

// Catalogue requests replace DOM sections as results arrive. Dispose their React roots on removal.
class MaterialRegion extends HTMLElement {
    connectedCallback(){
        if(!this.reactRoot)this.reactRoot=createRoot(this);
        flushSync(()=>this.reactRoot.render(themed(this.view)));
    }
    disconnectedCallback(){
        Promise.resolve().then(()=>{
            if(!this.isConnected && this.reactRoot){this.reactRoot.unmount();this.reactRoot=null;}
        });
    }
}
customElements.define('seans-region',MaterialRegion);
function region(view,className){
    const host=document.createElement('seans-region');host.view=view;host.className=className;return host;
}

const sections=[['home','Каталог',MovieIcon],['search','Поиск',SearchIcon],['library','Моя коллекция',LibraryIcon]];
function Chrome({active}){
    return <>
        <AppBar position="static" color="transparent" elevation={0}>
            <Toolbar disableGutters className="app-toolbar">
                <div className="brand"><i className="mark" aria-hidden="true"/>Сеанс</div>
                <IconButton id="settings" aria-label="Настройки" onClick={()=>window.settings()}><SettingsIcon/></IconButton>
            </Toolbar>
        </AppBar>
        <nav id="tabs" aria-label="Разделы">{sections.map(([id,label,Icon])=>
            <Button key={id} data-tab={id} className={active===id?'on':''} aria-current={active===id?'page':undefined}
                variant={active===id?'contained':'text'} startIcon={<Icon/>} onClick={()=>window.navigate(id)}>{label}</Button>)}</nav>
    </>;
}
const chrome=createRoot(document.getElementById('chrome'));
function selectTab(tab){flushSync(()=>chrome.render(themed(<Chrome active={tab}/>)));}
selectTab('home');
const surface=createRoot(document.getElementById('veil'));
flushSync(()=>surface.render(themed(<Paper component="section" id="modal" className="modal" role="dialog"
    aria-modal="true" aria-labelledby="modalTitle" elevation={8}/>)));

function PosterGrid({entries}){
    return entries.map(({item,title,meta,focusId,open,progress})=>
        <CardActionArea key={focusId||item.url} className="card" data-url={item.url} data-focus-id={focusId}
            onClick={e=>open(e.currentTarget)}>
            <span className="art">
                <span className="art-fallback" aria-hidden="true">{(title||item.title||'').slice(0,1)}</span>
                {item.poster?.startsWith('https://') && <img src={item.poster} alt="" loading="lazy" onError={e=>{e.currentTarget.hidden=true;}}/>}
                {item.score && <span className="score">★ {item.score}</span>}
                {progress>0 && <span className="progress"><i style={{width:Math.min(100,Math.round(progress*100))+'%'}}/></span>}
            </span>
            <strong>{title||item.title}</strong><small>{meta}</small>
        </CardActionArea>);
}
function ModeOptions({choose}){
    return [['tv','Телевизор','Управление пультом',TvIcon],['mobile','Телефон','Сенсорное управление',PhoneIcon]].map(([id,label,hint,Icon])=>
        <Button key={id} variant="outlined" data-mode={id} className="mode-choice" startIcon={<Icon/>} onClick={()=>choose(id)}>
            <span><strong>{label}</strong><small>{hint}</small></span>
        </Button>);
}
function SettingsChoices({tv,maximum,sites,changeMode,changeSites,setMaximum}){
    const [enabled,setEnabled]=useState(maximum);
    return <>
        <Button variant="outlined" startIcon={tv?<TvIcon/>:<PhoneIcon/>} onClick={changeMode}>Режим · {tv?'Телевизор':'Телефон'}</Button>
        <FormControlLabel className="quality-switch" labelPlacement="start" label="Всегда максимальное качество"
            control={<Switch checked={enabled} onChange={(_,value)=>{setMaximum(value);setEnabled(value);}}/>}/>
        <Button variant="outlined" onClick={changeSites}>Источники поиска · {sites}</Button>
    </>;
}
function EntryField({tv,htmlInput,...props}){
    const [editing,setEditing]=useState(false);
    function activate(input){
        if(!tv || editing)return;
        input.blur();
        flushSync(()=>setEditing(true));
        input.focus();
    }
    return <TextField {...props} slotProps={{
        input:{readOnly:tv && !editing},
        htmlInput:{...htmlInput,onClick:e=>activate(e.currentTarget),onBlur:()=>setEditing(false),
            onKeyDown:e=>{
                if(tv && !editing && e.key==='Enter'){e.preventDefault();activate(e.currentTarget);}
            }}
    }}/>;
}
function SearchForm({tv,draft,initial,open,fields,onDraft,onSubmit,onToggle,onChange,onReset,count}){
    const [expanded,setExpanded]=useState(open);
    const [filters,setFilters]=useState(initial);
    function update(key,value){
        const next={...filters,[key]:value};setFilters(next);onChange(key,value);
    }
    return <>
        <form className="mui-search-form" onSubmit={e=>{e.preventDefault();onSubmit(e.currentTarget.elements.query.value);}}>
            <EntryField tv={tv} fullWidth name="query" id="title-query" label="Название или ссылка" type="search" defaultValue={draft}
                onChange={e=>onDraft(e.target.value)} htmlInput={{enterKeyHint:'search',autoComplete:'off'}}/>
            <Button type="submit" variant="contained" startIcon={<SearchIcon/>}>Найти</Button>
        </form>
        <Button id="search-filter-toggle" variant={expanded?'contained':'outlined'} startIcon={<TuneIcon/>}
            aria-expanded={expanded} aria-controls="search-filters" onClick={()=>{setExpanded(!expanded);onToggle(!expanded);}}>
            Фильтры{count(filters)?' · '+count(filters):''}
        </Button>
        <Paper id="search-filters" className="mui-filters" variant="outlined" hidden={!expanded}>
            {fields.map(({key,label,choices})=>choices?
                <TextField key={key} id={'filter-'+key} select label={label} value={filters[key]} onChange={e=>update(key,e.target.value)}
                    slotProps={{inputLabel:{shrink:true},select:{native:true,inputProps:{'data-filter':key}}}}>
                    {choices.map(([v,text])=><option key={v} value={v}>{text}</option>)}
                </TextField>:
                <EntryField tv={tv} key={key} id={'filter-'+key} label={label} value={filters[key]} placeholder="Любой" onChange={e=>update(key,e.target.value.trim())}
                    htmlInput={{inputMode:'numeric',maxLength:4,'data-filter':key}}/>)}
            <Button variant="text" onClick={onReset}>Сбросить фильтры</Button>
        </Paper>
    </>;
}
window.SeansUI={
    selectTab,
    grid:entries=>region(<PosterGrid entries={entries}/>,'grid'),
    modes:choose=>region(<ModeOptions choose={choose}/>,'mui-modes'),
    playActions:(label,external,play)=>region(<>
        <Button variant="contained" className="primary" startIcon={<PlayIcon/>} onClick={()=>play(false)}>{label}</Button>
        {external && <Button variant="outlined" onClick={()=>play(true)}>В другом плеере</Button>}
    </>,'mui-play-actions'),
    parts:(items,active,isSeason,choose)=>region(items.map(part=>
        <Chip key={part.url} component="button" type="button" clickable label={part.label}
            color={active===part.url?'primary':'default'} aria-pressed={active===part.url}
            data-episode={isSeason?undefined:part.url} onClick={()=>choose(part,isSeason)}/>),'wrap mui-parts'),
    settings:props=>region(<SettingsChoices {...props}/>,'mui-settings'),
    search:props=>region(<SearchForm {...props}/>,'mui-search')
};
