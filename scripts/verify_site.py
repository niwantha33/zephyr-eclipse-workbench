#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check packaged p2 metadata, original bundle contents and archive integrity."""
from pathlib import Path
import argparse
import hashlib
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--site', type=Path, default=root / 'releng/repository/target/repository')
parser.add_argument('--archive', type=Path)
args = parser.parse_args()
site = args.site
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
            plugin = ET.fromstring(z.read('plugin.xml'))
            for page in plugin.findall("./extension[@point='org.eclipse.ui.preferencePages']/page"):
                if page.attrib['class'].replace('.', '/') + '.class' not in z.namelist():
                    raise SystemExit('Preference page class missing.')
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
archives = [args.archive] if args.archive else list(site.parent.glob('workbench-repository-*.zip'))
if len(archives) != 1:
    raise SystemExit('Expected one packaged update-site ZIP; use a clean build.')
archive = archives[0]
with zipfile.ZipFile(archive) as z:
    if z.testzip() is not None or 'p2.index' not in z.namelist():
        raise SystemExit('Invalid update-site ZIP.')
print('Verified p2 metadata and feature.')
print('Update-site SHA-256:', hashlib.sha256(archive.read_bytes()).hexdigest())
print('This checks packaging; it does not establish desktop installation or hardware support.')
