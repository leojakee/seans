import {build} from 'esbuild';
import {readFile, writeFile, readdir, realpath} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const here=path.dirname(fileURLToPath(import.meta.url));
const assets=path.resolve(here,'../app/src/main/assets');
const result=await build({
    absWorkingDir:here, entryPoints:['material.jsx'], bundle:true, format:'iife',
    target:'chrome80', minify:true, legalComments:'none', metafile:true,
    define:{'process.env.NODE_ENV':'"production"'}, outfile:path.join(assets,'material.js')
});

// Ship notices for the packages whose code is actually included in the APK.
const packages=new Map();
for(const output of Object.values(result.metafile.outputs)){
    for(const [file,details] of Object.entries(output.inputs)){
        if(!details.bytesInOutput || !file.includes('node_modules/'))continue;
        // realpath returns backslashes on Windows; the package lookup below expects POSIX separators.
        const absolute=(await realpath(path.resolve(here,file))).split(path.sep).join('/');
        const boundary=absolute.lastIndexOf('/node_modules/');
        const parts=absolute.slice(boundary+14).split('/');
        const name=parts[0][0]==='@'?parts.slice(0,2).join('/'):parts[0];
        packages.set(name,absolute.slice(0,boundary+14)+name);
    }
}
const notices=[];
for(const [name,directory] of [...packages].sort(([a],[b])=>a.localeCompare(b,'en'))){
    const metadata=JSON.parse(await readFile(path.join(directory,'package.json'),'utf8'));
    const files=(await readdir(directory)).filter(f=>/^(?:licen[cs]e|copying|notice)(?:[.-].*)?$/i.test(f)).sort();
    if(!files.length)throw Error('License file missing for '+name);
    const licenses=await Promise.all(files.map(f=>readFile(path.join(directory,f),'utf8')));
    notices.push(name+' '+metadata.version+'\n'+licenses.join('\n').trim());
}
await writeFile(path.join(assets,'material.NOTICES.txt'),notices.join('\n\n')+'\n');
