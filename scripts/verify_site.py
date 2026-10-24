#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check packaged p2 metadata, original bundle contents and archive integrity."""
from pathlib import Path
import argparse
import tempfile
import hashlib
import xml.etree.ElementTree as ET
import zipfile
import json

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--site', type=Path)
parser.add_argument('--archive', type=Path)
args = parser.parse_args()
version = ET.parse(root / 'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
archive = args.archive or root / 'releng/repository/target' / f'workbench-repository-{version}.zip'
temporary = tempfile.TemporaryDirectory(prefix='zew-verify-site-')
site = args.site or Path(temporary.name)
if args.site is None:
    # Verify the distributable itself, avoiding stale exploded build output.
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if not (site / name).resolve().is_relative_to(site.resolve()):
                raise SystemExit('Unsafe archive path.')
        z.extractall(site)
expected = {'io.github.niwantha33.zew.core', 'io.github.niwantha33.zew.ui'}
bundles = list((site / 'plugins').glob('*.jar'))
if {p.name.split('_', 1)[0] for p in bundles} != expected:
    raise SystemExit('Unexpected bundled dependencies or missing plug-ins.')
for p in bundles:
    with zipfile.ZipFile(p) as z:
        if z.testzip() is not None:
            raise SystemExit(f'Corrupt archive: {p.name}')
        if z.read('LICENSE').strip() != (root / 'LICENSE').read_bytes().strip():
            raise SystemExit(f'Missing or incorrect licence: {p.name}')
        if not any(n.endswith('.class') for n in z.namelist()):
            raise SystemExit(f'No compiled classes: {p.name}')
        if '.ui_' in p.name:
            if 'tools/inventory.py' not in z.namelist():
                raise SystemExit('Tools inventory helper missing.')
            compile(z.read('tools/inventory.py'), 'tools/inventory.py', 'exec')
            compile(z.read('tools/setup.py'), 'tools/setup.py', 'exec')
            compile(z.read('tools/managed_sitecustomize.py'), 'tools/managed_sitecustomize.py', 'exec')
            for helper in ('setup.py', 'managed_sitecustomize.py'):
                if z.read('tools/' + helper) != (root / 'plugins/io.github.niwantha33.zew.ui/tools' / helper).read_bytes():
                    raise SystemExit('Packaged helper differs from verified source: ' + helper)
            lock = json.loads(z.read('tools/setup-lock.json'))
            if lock['profile'] != 'first-build-win-x64' or any(p['review'] != 'approved-build-use' for p in lock['packages']):
                raise SystemExit('Setup profile or package review is incomplete.')
            if not z.read('tools/setup-licences.txt').strip():
                raise SystemExit('Setup licence records missing.')
            for name in ('SetupWizard', 'SetupStartup', 'ZephyrProjectBuilder', 'ZephyrProjectWizard'):
                if 'io/github/niwantha33/zew/ui/' + name + '.class' not in z.namelist():
                    raise SystemExit('Beginner setup class missing: ' + name)
            if 'io/github/niwantha33/zew/ui/ToolsDialog.class' not in z.namelist():
                raise SystemExit('Tools manager dialog missing.')
            plugin = ET.fromstring(z.read('plugin.xml'))
            for page in plugin.findall("./extension[@point='org.eclipse.ui.preferencePages']/page"):
                if page.attrib['class'].replace('.', '/') + '.class' not in z.namelist():
                    raise SystemExit('Preference page class missing.')
            for point in ('org.eclipse.ui.newWizards', 'org.eclipse.ui.importWizards'):
                registrations = plugin.findall("./extension[@point='" + point + "']/wizard")
                if not any(w.attrib.get('class') == 'io.github.niwantha33.zew.ui.ZephyrProjectWizard' for w in registrations):
                    raise SystemExit('New/Import Zephyr wizard not registered for ' + point)
            for handler in plugin.findall("./extension[@point='org.eclipse.ui.handlers']/handler"):
                if handler.attrib['class'].replace('.', '/') + '.class' not in z.namelist():
                    raise SystemExit('Command handler class missing.')
    print('Verified bundle:', p.name)
for name, entry in [('content.jar', 'content.xml'), ('artifacts.jar', 'artifacts.xml')]:
    with zipfile.ZipFile(site / name) as z:
        if z.testzip() is not None:
            raise SystemExit(f'Corrupt metadata: {name}')
        ET.fromstring(z.read(entry))
features = list((site / 'features').glob('*.jar'))
if len(features) != 1:
    raise SystemExit('Expected one installable feature.')
with zipfile.ZipFile(features[0]) as z:
    feature = ET.fromstring(z.read('feature.xml'))
    if {p.attrib['id'] for p in feature.findall('plugin')} != expected:
        raise SystemExit('Feature does not reference the two original bundles.')
    if z.read('LICENSE').strip() != (root / 'LICENSE').read_bytes().strip():
        raise SystemExit('Feature licence missing or incorrect.')
with zipfile.ZipFile(archive) as z:
    if z.testzip() is not None or 'p2.index' not in z.namelist():
        raise SystemExit('Invalid update-site ZIP.')
print('Verified p2 metadata and feature.')
print('Update-site SHA-256:', hashlib.sha256(archive.read_bytes()).hexdigest())
print('This checks packaging; it does not establish desktop installation or hardware support.')

