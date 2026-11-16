"""Provision project-local ignored Android build toolchain."""
import concurrent.futures
import json
import os
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tooling'
TOOLS.mkdir(exist_ok=True)

def get(name, url):
    dest = TOOLS / name
    if not dest.exists():
        print('Downloading', name, flush=True)
        with urllib.request.urlopen(url, timeout=180) as r, dest.open('wb') as f:
            while block := r.read(1024 * 1024):
                f.write(block)
    print(name, dest.stat().st_size, flush=True)
    return dest

def setup_jdk():
    archive = get('jdk17.zip', 'https://aka.ms/download-jdk/microsoft-jdk-17.0.21.0.2-windows-x64.zip')
    target = TOOLS / 'java'
    if not target.exists():
        with zipfile.ZipFile(archive) as z:
            top = z.namelist()[0].split('/')[0]
            z.extractall(TOOLS)
        (TOOLS / top).rename(target)
    print('JAVA_HOME=' + str(target), flush=True)

def setup_sdk():
    archive = get('android-cli.zip', 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip')
    target = TOOLS / 'android-sdk' / 'cmdline-tools'
    target.mkdir(parents=True, exist_ok=True)
    if not (target / 'latest').exists():
        with zipfile.ZipFile(archive) as z:
            z.extractall(target)
        (target / 'cmdline-tools').rename(target / 'latest')
    print('ANDROID_HOME=' + str(TOOLS / 'android-sdk'), flush=True)

def setup_gradle():
    archive = get('gradle.zip', 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip')
    if not (TOOLS / 'gradle-8.11.1').exists():
        with zipfile.ZipFile(archive) as z:
            z.extractall(TOOLS)
    print('Gradle ready', flush=True)

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    for done in concurrent.futures.as_completed([pool.submit(fn) for fn in (setup_jdk, setup_sdk, setup_gradle)]):
        done.result()
print('Tool downloads complete.', flush=True)
