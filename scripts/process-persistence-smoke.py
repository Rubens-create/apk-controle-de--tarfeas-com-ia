"""Destructive smoke test, restricted to the debug app on an emulator.
Creates a task through the real UI, force-stops Android, and verifies disk persistence.
"""
from pathlib import Path
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
sdk = Path(os.environ.get('ANDROID_HOME', str(root / '.tooling/android-sdk')))
adb = sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')
package = 'com.taskai.app.debug'
activity = package + '/com.taskai.app.MainActivity'
artifacts = root / 'artifacts'
artifacts.mkdir(exist_ok=True)

def command(*args, binary=False):
    return subprocess.check_output([str(adb), '-e', *args], text=not binary, timeout=45)

def screen():
    for _ in range(5):
        command('shell', 'rm', '-f', '/sdcard/taskai-test-ui.xml')
        result = command('shell', 'uiautomator', 'dump', '/sdcard/taskai-test-ui.xml')
        if 'dumped' in result.lower():
            return ET.fromstring(command('shell', 'cat', '/sdcard/taskai-test-ui.xml'))
        time.sleep(.3)
    raise AssertionError('Android UI hierarchy is not ready; stale coordinates were not used.')

def tap_node(predicate):
    for _ in range(12):
        tree = screen()
        node = next((n for n in tree.iter('node') if predicate(n)), None)
        if node is not None:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
            command('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
            return
        time.sleep(.3)
    raise AssertionError('Expected UI node not found.')

assert command('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Only a disposable emulator is allowed.'
print('Clearing only the test package:', package, flush=True)
command('shell', 'pm', 'clear', package)
command('shell', 'am', 'start', '-W', '-n', activity)
time.sleep(1)
(artifacts / 'dashboard-empty.png').write_bytes(command('exec-out', 'screencap', '-p', binary=True))
tap_node(lambda n: n.get('content-desc') == 'Criar tarefa')
tap_node(lambda n: n.get('class') == 'android.widget.EditText')
title = 'Persistencia'
time.sleep(.5)
command('shell', 'input', 'text', title)
time.sleep(.5)
assert any(n.get('class') == 'android.widget.EditText' and n.get('text') == title for n in screen().iter('node')), 'UI typing did not deliver the test title.'
tap_node(lambda n: n.get('text') == 'Salvar tarefa')
for _ in range(12):
    if not any(n.get('text') == 'Salvar tarefa' for n in screen().iter('node')):
        break
    time.sleep(.3)
else:
    raise AssertionError('Task editor did not close after saving.')
command('shell', 'am', 'force-stop', package)
command('shell', 'am', 'start', '-W', '-n', activity)
tap_node(lambda n: n.get('text') == 'Tarefas')
for _ in range(12):
    if any(n.get('text') == title for n in screen().iter('node')):
        print('PASS: the UI-created task survived force-stop and process restart.', flush=True)
        (artifacts / 'persistence-after-process-restart.png').write_bytes(command('exec-out', 'screencap', '-p', binary=True))
        break
    time.sleep(.3)
else:
    raise AssertionError('Saved task was not visible after process restart.')
command('shell', 'pm', 'clear', package)
command('shell', 'am', 'start', '-W', '-n', activity)
print('The disposable debug installation was cleared again; no demo data is shipped.', flush=True)
