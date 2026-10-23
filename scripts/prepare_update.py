#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Prepare immutable private-branch p2 content and its bounded channel manifest.

Publish release files first, then regenerate the manifest with that Git commit as
--revision. Publish channel.properties in a separate commit. No credentials used.
"""
from pathlib import Path
import argparse
import hashlib
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

def prepare(site, revision, output, notes):
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('revision must be the immutable commit containing the release files')
    features = list((site / 'features').glob('*.jar'))
    if len(features) != 1:
        raise ValueError('expected one workbench feature')
    with zipfile.ZipFile(features[0]) as archive:
        feature = ET.fromstring(archive.read('feature.xml'))
    if feature.attrib.get('id') != 'io.github.niwantha33.zew.feature':
        raise ValueError('unexpected feature identity')
    version = feature.attrib['version']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+\.[A-Za-z0-9_-]+', version):
        raise ValueError('expected a qualified release version')
    files = sorted(p for p in site.rglob('*') if p.is_file())
    if not 3 <= len(files) <= 100:
        raise ValueError('invalid file count')
    records = []
    total = 0
    for path in files:
        name = path.relative_to(site).as_posix()
        if path.is_symlink() or not re.fullmatch(r'(?:p2\.index|(?:content|artifacts)\.(?:jar|xml|xml\.xz)|(?:plugins|features)/[A-Za-z0-9_.-]+\.jar)', name):
            raise ValueError('unexpected update-site content')
        size = path.stat().st_size
        if not 1 <= size <= 8_388_608:
            raise ValueError('invalid individual file size')
        total += size
        records.append((name, size, hashlib.sha256(path.read_bytes()).hexdigest()))
    if total > 67_108_864:
        raise ValueError('update exceeds total limit')
    output.mkdir(parents=True, exist_ok=False)
    destination = output / 'releases' / version
    shutil.copytree(site, destination)
    notes = notes.replace('\\', '\\\\').replace('\r', ' ').replace('\n', ' ')
    lines = ['schema=1', 'feature=io.github.niwantha33.zew.feature.feature.group',
             f'version={version}', f'revision={revision}', f'directory=releases/{version}',
             f'notes={notes}', f'files={len(records)}']
    for i, (name, size, digest) in enumerate(records):
        lines.extend([f'file.{i}.path={name}', f'file.{i}.size={size}', f'file.{i}.sha256={digest}'])
    (output / 'channel.properties').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'Prepared {version}: {len(records)} files, {total} bytes')
    return version

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--site', type=Path, default=Path('releng/repository/target/repository'))
    parser.add_argument('--revision', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--notes', default='Workbench maintenance update.')
    args = parser.parse_args()
    prepare(args.site, args.revision, args.output, args.notes)
