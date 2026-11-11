import {readFileSync,existsSync,readdirSync,lstatSync} from 'node:fs';
import assert from 'node:assert/strict';
const release=JSON.parse(readFileSync('site/release.json','utf8'));
const html=readFileSync('site/index.html','utf8');
const manifest=readFileSync('app/src/main/AndroidManifest.xml','utf8');
assert.match(release.version,/^\d+\.\d+\.\d+$/);
assert.ok(manifest.includes('android:versionName="' + release.version + '"'));
assert.equal(release.tag, 'v' + release.version);
assert.equal(release.download, release.repository + '/releases/download/' + release.tag + '/' + release.file);
assert.match(release.sha256,/^[a-f0-9]{64}$/);
assert.ok(Number.isSafeInteger(release.size) && release.size > 0);
assert.equal(readFileSync('verificacion/SHA256SUMS.txt','utf8').trim(), release.sha256 + '  ' + release.file);
assert.ok(html.includes(release.download)&&html.includes(release.sha256));
assert.ok(!manifest.includes('android.permission.INTERNET'));
assert.ok(manifest.includes("android.permission.MANAGE_EXTERNAL_STORAGE"));
for(const root of ['site','app','scripts','tests','verificacion']){
  function walk(path){for(const item of readdirSync(path,{withFileTypes:true})){const full=`${path}/${item.name}`;if(item.isDirectory()){if(item.name==='__pycache__')continue;walk(full);}else{assert.ok(!/\.(p12|jks|keystore|pem|key)$|password\.txt|firma-privada\.zip/i.test(item.name),`Private artifact: ${full}`);}}}
  walk(root);
}
assert.ok(!existsSync('site/signing'));
console.log('Archivos Luma ' + release.version + ': landing, permissions and release metadata verified.');
