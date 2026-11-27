const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname,'../app/src/main/assets/web-quality.js'),'utf8');

function collaps() {
    const messages=[];
    const channel={postMessage:data=>messages.push(JSON.parse(data))};
    const video={muted:false,paused:true,addEventListener(){},removeEventListener(){},
        pause(){this.paused=true;},play(){this.paused=false;return Promise.resolve();}};
    const items=['480p','720p','1080p'].map((textContent,i)=>({
        textContent,className:i===0?'item_a active_b':'item_a',isConnected:true,
        click(){for(const item of items)item.className='item_a';this.className+=' active_b';}
    }));
    const document={querySelector:()=>video,querySelectorAll:q=>
        q.endsWith(' li')?items:[{querySelectorAll:()=>items}]};
    vm.runInNewContext(script,{window:{SeansVideoQuality:channel},location:{hostname:'api.embf.net'},document,setTimeout});
    return {messages,video,items,command:async(action,height)=>{
        channel.onmessage({data:JSON.stringify({action,height})});
        await new Promise(resolve=>setImmediate(resolve));
    }};
}

test('lists actual qualities, confirms the choice and starts only after the start command',async()=>{
    const p=collaps();
    await p.command('prepare');
    assert.deepEqual(p.messages,[{type:'ready'},{type:'options',heights:[480,720,1080],current:480}]);
    assert.equal(p.video.paused,true);
    await p.command('select',1080);
    assert.deepEqual(p.messages.at(-1),{type:'applied',height:1080});
    assert.match(p.items[2].className,/active_/);
    assert.equal(p.video.muted,false);
    assert.equal(p.video.paused,true);
    await p.command('start');
    assert.equal(p.video.paused,false);
});

test('a choice that disappears is reported and playback stays paused',async()=>{
    const p=collaps();await p.command('prepare');
    p.items[1].isConnected=false;
    await p.command('select',720);
    assert.deepEqual(p.messages.at(-1),{type:'error'});
    assert.equal(p.video.paused,true);
    await p.command('release');
    assert.equal(p.video.muted,false);
    assert.equal(p.video.paused,true);
});
