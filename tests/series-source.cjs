const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const store = new Map();
const app = vm.createContext({
    URL,
    localStorage: {getItem: k => store.get(k), setItem: (k,v) => store.set(k,v)},
    readJson: (v,fallback) => JSON.parse(v || fallback)
});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/playback.js'),'utf8'), app);
const item = {url:'https://rukino.ru/series/14610135'};
const ctx = (season,episode) => ({item,id:'14610135',seasonKey:String(season),episodeKey:String(episode)});
test('episode override, season default and first viewing of another season', () => {
    const a={method:'collaps',label:'Collaps',base:'14610135'}, b={method:'vibix',label:'Vibix',base:'https://kinonox.com/watch/tv/291507'};
    assert.equal(app.sourcePreferences(ctx(1,2)).choice,undefined);
    app.rememberPlayer(ctx(1,2),a,true);
    assert.equal(app.sourcePreferences(ctx(1,3)).choice.label,'Collaps');
    app.rememberPlayer(ctx(1,3),b,true);
    assert.equal(app.sourcePreferences(ctx(1,2)).choice.label,'Collaps');
    assert.equal(app.sourcePreferences(ctx(1,4)).choice.label,'Vibix');
    assert.equal(app.sourcePreferences(ctx(2,1)).choice,undefined);
    assert.equal(app.sourcePreferences(ctx(2,1)).suggestion.label,'Vibix');
    assert.equal(app.editionNote({url:'https://rutube.ru/play/embed/example'}),'✂ Возможны сокращения');
    assert.equal(app.editionNote({url:'https://example.org/?site=rutube.ru'}),'');
});
test('a missing saved source opens the picker and never starts a different episode',async()=>{
    const dialog={}, cancel={isConnected:true};let reason;
    Object.assign(app,{seriesContext:()=>ctx(1,2),beginDialog:()=>{},btn:()=>cancel,modal:{append(){}},focus:()=>{},
        otherSites:(external,msg)=>{reason=msg;},request:async()=>({}),Cinema:{play:()=>assert.fail('unexpected playback')}});
    app.rememberPlayer(ctx(1,2),{method:'collaps',label:'Collaps',base:'14610135'},true);
    await app.playSeries(false);
    assert.match(reason,/Collaps/);
});
