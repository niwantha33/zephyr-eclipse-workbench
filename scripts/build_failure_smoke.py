#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the installed failure investigation view in a disposable Eclipse.

The supplied Eclipse installation is updated from the explicit local p2 site.
A display is required. No debugger, probe or firmware build is used.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--eclipse', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--site', type=Path, required=True)
    args = parser.parse_args()
    eclipse = args.eclipse.resolve()
    jdk = args.java_home.resolve()
    launcher = next((eclipse / 'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
    def run(command):
        return subprocess.run(list(map(str, command)), check=True, timeout=120)
    director = [jdk / 'bin/java', '-jar', launcher, '-nosplash', '-application',
                'org.eclipse.equinox.p2.director', '-destination', eclipse,
                '-profile', 'SDKProfile', '-roaming']
    roots = subprocess.check_output(list(map(str, [*director, '-listInstalledRoots'])), text=True, timeout=120)
    previous = [line.strip() for line in roots.splitlines()
                if line.startswith('io.github.niwantha33.zew.feature.feature.group/')]
    installation = [*director, '-repository', args.site.resolve().as_uri(), '-installIU',
                    'io.github.niwantha33.zew.feature.feature.group']
    if previous:
        run([*director, '-uninstallIU', ','.join(previous)])
    run(installation)
    with tempfile.TemporaryDirectory(prefix='zew-build-failure-smoke-') as directory:
        temporary = Path(directory)
        classes = temporary / 'classes'
        classes.mkdir()
        classpath = os.pathsep.join(str(path) for path in (eclipse / 'plugins').glob('*.jar'))
        run([jdk / 'bin/javac', '--release', '21', '-encoding', 'UTF-8', '-cp', classpath,
             '-d', classes, ROOT / 'tests/eclipse/BuildFailureInvestigationSmoke.java'])
        jar = temporary / 'build-failure-smoke.jar'
        with zipfile.ZipFile(jar, 'w') as bundle:
            bundle.writestr('META-INF/MANIFEST.MF',
                'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\n'
                'Bundle-SymbolicName: zew.build.failure.smoke;singleton:=true\n'
                'Bundle-Version: 1.0.0\n'
                'Require-Bundle: org.eclipse.equinox.app,org.eclipse.core.runtime,'
                'org.eclipse.core.resources,org.eclipse.ui,org.eclipse.jface,org.eclipse.swt\n\n')
            bundle.writestr('plugin.xml', '<plugin><extension point="org.eclipse.core.runtime.applications" id="run">'
                '<application><run class="zew.smoke.BuildFailureInvestigationSmoke"/></application></extension></plugin>')
            for path in classes.rglob('*.class'):
                bundle.write(path, path.relative_to(classes).as_posix())
        config = eclipse / 'configuration/config.ini'
        original = config.read_bytes()
        try:
            text = original.decode('utf-8')
            modified = '\n'.join(line + ',reference:' + jar.as_uri() + '@4:start'
                if line.startswith('osgi.bundles=') else line for line in text.splitlines()) + '\n'
            if modified == text or 'osgi.bundles=' not in text:
                raise RuntimeError('Eclipse config.ini lacks osgi.bundles registration')
            config.write_text(modified, encoding='utf-8')
            run([jdk / 'bin/java', '-jar', launcher, '-nosplash', '-consolelog', '-clean',
                 '-data', temporary / 'workspace', '-application', 'zew.build.failure.smoke.run'])
        finally:
            config.write_bytes(original)


if __name__ == '__main__':
    main()
