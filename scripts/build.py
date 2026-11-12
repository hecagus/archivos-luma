#!/usr/bin/env python3
"""Build a small universal, signed APK with Google's SDK tools and Eclipse ECJ.

Set LUMA_ANDROID_JAR, LUMA_BUILD_TOOLS and LUMA_ECJ_JAR to use an installed
toolchain, or put the pinned packages in .toolchain (see README).
"""
import hashlib, os, pathlib, secrets, shutil, subprocess, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
os.chdir(ROOT)
TOOLS = pathlib.Path(os.environ.get('LUMA_BUILD_TOOLS', ROOT/'.toolchain/build-tools/android-16'))
ANDROID = pathlib.Path(os.environ.get('LUMA_ANDROID_JAR', ROOT/'.toolchain/platform/android-36/android.jar'))
ECJ = pathlib.Path(os.environ.get('LUMA_ECJ_JAR', ROOT/'.toolchain/ecj.jar'))
OUT = ROOT/'output'
BUILD = ROOT/'build/manual'
SIGN = ROOT/'signing'

def run(*args):
    subprocess.run([str(a) for a in args], check=True)

for item in [TOOLS/'aapt2', TOOLS/'d8', TOOLS/'apksigner', TOOLS/'zipalign', ANDROID, ECJ]:
    if not item.exists(): raise SystemExit(f'Missing tool: {item}. See README.md.')
if BUILD.exists(): shutil.rmtree(BUILD)
for directory in [OUT, BUILD/'classes', BUILD/'dex', BUILD/'generated', SIGN]: directory.mkdir(parents=True, exist_ok=True)
SIGN.chmod(0o700)
password = SIGN/'password.txt'
key = SIGN/'archivos-luma-updates.p12'
if not key.exists():
    password.write_text(secrets.token_urlsafe(32)); password.chmod(0o600)
    run('keytool','-genkeypair','-alias','archivos-luma','-keyalg','RSA','-keysize','3072','-validity','10000',
        '-storetype','PKCS12','-keystore',key,'-storepass:file',password,'-keypass:file',password,
        '-dname','CN=Archivos Luma by HecAgus, O=HecAgus, C=MX')
    key.chmod(0o600)
if not password.exists(): raise SystemExit('Restore the matching signing password before building an update.')
run(TOOLS/'aapt2','compile','--dir',ROOT/'app/src/main/res','-o',BUILD/'resources.zip')
run(TOOLS/'aapt2','link','-o',BUILD/'resources.apk','-I',ANDROID,'--manifest',ROOT/'app/src/main/AndroidManifest.xml',
    '--java',BUILD/'generated','--min-sdk-version','29','--target-sdk-version','36',BUILD/'resources.zip')
sources = list((ROOT/'app/src/main/java').rglob('*.java')) + list((BUILD/'generated').rglob('*.java'))
run('java','-jar',ECJ,'-1.8','-encoding','UTF-8','-warn:none','-classpath',ANDROID,'-d',BUILD/'classes',*sources)
classes=list((BUILD/'classes').rglob('*.class'))
run(TOOLS/'d8','--release','--lib',ANDROID,'--min-api','29','--output',BUILD/'dex',*classes)
shutil.copy(BUILD/'resources.apk',BUILD/'unsigned.apk')
with zipfile.ZipFile(BUILD/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as apk:
    for dex in (BUILD/'dex').glob('*.dex'): apk.write(dex,dex.name)
run(TOOLS/'zipalign','-P','16','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
final=OUT/'Archivos-Luma-1.1.0.apk'
run(TOOLS/'apksigner','sign','--ks',key,'--ks-key-alias','archivos-luma','--ks-pass',f'file:{password}',
    '--v4-signing-enabled','false','--out',final,BUILD/'aligned.apk')
run(TOOLS/'apksigner','verify','--verbose','--print-certs',final)
run(TOOLS/'zipalign','-c','-P','16','4',final)
report=subprocess.check_output([str(TOOLS/'aapt2'),'dump','badging',str(final)],text=True)
(OUT/'apk-validation.txt').write_text(report)
permissions=subprocess.check_output([str(TOOLS/'aapt2'),'dump','permissions',str(final)],text=True)
assert 'android.permission.INTERNET' not in permissions
assert 'android.permission.MANAGE_EXTERNAL_STORAGE' in permissions
assert "minSdkVersion:'29'" in report and "targetSdkVersion:'36'" in report
with zipfile.ZipFile(final) as apk:
    assert 'classes.dex' in apk.namelist()
    assert not any(n.startswith('lib/') for n in apk.namelist()), 'APK must remain universal, with no native libraries.'
digest=hashlib.sha256(final.read_bytes()).hexdigest()
(OUT/'SHA256SUMS.txt').write_text(f'{digest}  {final.name}\n')
print(f'\nREADY: {final.name} ({final.stat().st_size:,} bytes). Android 10+, universal, signed.')
