"""Install and exercise an already-built APK without retaining a Gradle JVM.

Use only on a disposable emulator/test installation: instrumentation clears app data.
"""
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--class', dest='test_class', help='Optional fully qualified JUnit test class')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME', str(root / '.tooling/android-sdk')))
adb = sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
if not adb.exists():
    found = shutil.which('adb')
    if not found:
        sys.exit('Configure ANDROID_HOME or put adb on PATH.')
    adb = Path(found)

def run(*parts, **kwargs):
    return subprocess.run([str(adb), '-e', *parts], cwd=root, check=True, **kwargs)

print('Waiting for the disposable emulator to finish booting.', flush=True)
run('wait-for-device', timeout=180)
for _ in range(90):
    status = run('shell', 'getprop', 'sys.boot_completed', text=True, capture_output=True).stdout.strip()
    if status == '1':
        break
    time.sleep(2)
else:
    sys.exit('Emulator did not complete boot.')
for key in ['window_animation_scale', 'transition_animation_scale', 'animator_duration_scale']:
    run('shell', 'settings', 'put', 'global', key, '0', capture_output=True)
run('shell', 'input', 'keyevent', '82', capture_output=True)
for apk in ['app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
    run('install', '-r', str(root / apk), timeout=180)

command = [str(adb), '-e', 'shell', 'am', 'instrument', '-w']
if args.test_class:
    command += ['-e', 'class', args.test_class]
command += ['com.taskai.app.debug.test/androidx.test.runner.AndroidJUnitRunner']
print('Running real Android instrumentation, without a paid API key.', flush=True)
result = subprocess.run(command, cwd=root, text=True, capture_output=True, timeout=900)
output = result.stdout + result.stderr
artifacts = root / 'artifacts'
artifacts.mkdir(exist_ok=True)
log = artifacts / 'device-instrumentation.log'
log.write_text(output, encoding='utf-8')
print(output, flush=True)
print('Instrumentation report:', log, flush=True)
if result.returncode or 'FAILURES!!!' in output or not re.search(r'OK \(\d+ tests?\)', output):
    sys.exit(1)
