#!/usr/bin/env python3
"""Linux build tools. Downloads are pinned and verified, not bundled in the app."""
from pathlib import Path
import concurrent.futures,hashlib,urllib.request,zipfile
root=Path(__file__).resolve().parents[1]/'.toolchain';root.mkdir(exist_ok=True)
packages=[
 ('https://dl.google.com/android/repository/platform-36_r02.zip','2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576','sha1','platform.zip','platform'),
 ('https://dl.google.com/android/repository/build-tools_r36_linux.zip','b0b6376977657e8ad9b969bacf4093601da2c6fb','sha1','build-tools.zip','build-tools'),
 ('https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.43.0/ecj-3.43.0.jar','c786468c65e906498e7e36ece4e0d04c6d3dd34c9a61b34a3a5b512801911a82','sha256','ecj.jar',None)
]
def download(package):
    url,digest,algorithm,name,folder=package;file=root/name
    if not file.exists():
        with urllib.request.urlopen(url,timeout=90) as response,file.open('wb') as stream:
            while data:=response.read(1024*1024):stream.write(data)
    if hashlib.new(algorithm,file.read_bytes()).hexdigest()!=digest:raise RuntimeError('Checksum mismatch: '+name)
    if folder:
        with zipfile.ZipFile(file) as archive:archive.extractall(root/folder)
        for binary in (root/folder).rglob('*'):
            if binary.is_file() and binary.suffix in ['', '.sh']:binary.chmod(binary.stat().st_mode|0o111)
    print(name+' verified',flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(download,packages))
