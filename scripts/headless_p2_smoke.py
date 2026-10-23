#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Install successive workbench sites into a temporary COPY of Eclipse using p2.

The supplied Eclipse installation is not modified. This validates provisioning,
not the desktop wizard, credentials UI, notifications or hardware workflows.
"""
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

FEATURE = 'io.github.niwantha33.zew.feature.feature.group'

def smoke(eclipse, java, sites):
    with tempfile.TemporaryDirectory(prefix='zew-p2-smoke-') as temporary:
        installation = Path(temporary) / 'eclipse'
        shutil.copytree(eclipse, installation, symlinks=True)
        launcher = next((installation / 'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
        config = (installation / 'configuration/config.ini').read_text()
        profile = re.search(r'^eclipse.p2.profile=(.+)$', config, re.M).group(1).strip()
        common = [str(java), '-XX:-UsePerfData', '-jar', str(launcher), '-nosplash', '-consolelog',
                  '-application', 'org.eclipse.equinox.p2.director', '-destination', str(installation),
                  '-profile', profile, '-roaming']
        previous = None
        for site in sites:
            feature_jar = next((site / 'features').glob('io.github.niwantha33.zew.feature_*.jar'))
            with zipfile.ZipFile(feature_jar) as archive:
                version = ET.fromstring(archive.read('feature.xml')).attrib['version']
            command = [*common, '-repository', site.resolve().as_uri(), '-installIU', FEATURE + '/' + version]
            if previous:
                command += ['-uninstallIU', FEATURE + '/' + previous]
            result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
            print(result.stdout, flush=True)
            if result.returncode:
                raise RuntimeError(f'p2 provisioning failed for {version}')
            listed = subprocess.run([*common, '-listInstalledRoots'], text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=60, check=True).stdout
            if FEATURE + '/' + version not in listed:
                raise RuntimeError(f'Updated feature not present: {version}')
            if previous and FEATURE + '/' + previous in listed:
                raise RuntimeError('Old root feature was not replaced')
            print(f'PASS headless p2: {previous or "baseline"} -> {version}', flush=True)
            previous = version
        print('Headless install/update checks passed. Desktop and private-account tests remain separate.', flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--eclipse', required=True, type=Path)
    parser.add_argument('--java', type=Path, default=Path(os.environ.get('JAVA_HOME', '')) / 'bin/java')
    parser.add_argument('--site', action='append', type=Path, required=True)
    args = parser.parse_args()
    smoke(args.eclipse.resolve(), args.java.resolve(), args.site)
