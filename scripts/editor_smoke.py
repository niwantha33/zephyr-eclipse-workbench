#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Test the installed editor plug-in in a disposable Eclipse containing CDT core.

--eclipse must be a disposable installation: this script installs the workbench
and adds a test-only bundle. No user Eclipse/workspace should be supplied.
The C fixture is compiled/preprocessed by the host GCC before CDT checks it.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--eclipse', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--site', type=Path, required=True)
parser.add_argument('--native-workbench', action='store_true', help='Also exercise the native importer/exporter in a full workbench; requires a display.')
args = parser.parse_args()
eclipse = args.eclipse.resolve(); jdk = args.java_home.resolve()
launcher = next((eclipse / 'plugins').glob('org.eclipse.equinox.launcher_*.jar'))
run = lambda command: subprocess.run(list(map(str, command)), check=True, timeout=120)
director = [str(jdk / 'bin/java'), '-jar', str(launcher), '-nosplash', '-application', 'org.eclipse.equinox.p2.director',
            '-destination', str(eclipse), '-profile', 'SDKProfile', '-roaming']
roots = subprocess.check_output([*director, '-listInstalledRoots'], text=True, timeout=60)
previous = [line.strip() for line in roots.splitlines() if line.startswith('io.github.niwantha33.zew.feature.feature.group/')]
installation = [*director, '-repository', args.site.resolve().as_uri(), '-installIU', 'io.github.niwantha33.zew.feature.feature.group']
if previous: installation += ['-uninstallIU', ','.join(previous)]
run(installation)

with tempfile.TemporaryDirectory(prefix='zew CDT editor Ω ') as directory:
    temporary = Path(directory); fixture = temporary / 'project'; (fixture / 'src').mkdir(parents=True)
    build = fixture / '.zew-build/first'; build.mkdir(parents=True)
    headers = temporary / 'headers/include/zephyr'; headers.mkdir(parents=True)
    (headers / 'kernel.h').write_text('void k_sleep(int duration);\n')
    forced = build / 'forced.h'; forced.write_text('typedef int forced_type;\n')
    macro = build / 'autoconf.h'; macro.write_text('#define CONFIG_ZEW_TEST 73\n')
    main = fixture / 'src/main.c'; main.write_text('#include <zephyr/kernel.h>\nforced_type test_value = CONFIG_ZEW_TEST;\nint main(void){ k_sleep(test_value); return 0; }\n')
    gcc = Path(shutil.which('gcc')).resolve()
    command = [str(gcc), '-I', str(headers.parent), '-imacros', str(macro), '-include', str(forced)]
    run([*command, '-c', main, '-o', build / 'main.o'])
    cpp = fixture / 'src/helper.cpp'; cpp.write_text('int cpp_user(void){return CPP_ONLY;}\n')
    gpp = Path(shutil.which('g++')).resolve(); cpp_command = [str(gpp), '-std=c++17', '-fno-exceptions', '-fno-rtti', '-DCPP_ONLY=11']
    run([*cpp_command, '-c', cpp, '-o', build / 'helper.o'])
    (build / 'compile_commands.json').write_text(json.dumps([
        {'directory': str(build), 'file': str(main), 'arguments': [*command, '-c', str(main)]},
        {'directory': str(build), 'file': str(cpp), 'arguments': [*cpp_command, '-c', str(cpp)]}]))
    spec = importlib.util.spec_from_file_location('editor', ROOT / 'plugins/io.github.niwantha33.zew.ui/tools/editor_settings.py')
    editor = importlib.util.module_from_spec(spec); spec.loader.exec_module(editor)
    editor.write(editor.collect(fixture, build, gcc.parent.parent), build / 'editor-settings.tsv')
    classes = temporary / 'classes'; classes.mkdir()
    classpath = os.pathsep.join(map(str, (eclipse / 'plugins').glob('*.jar')))
    run([jdk / 'bin/javac', '--release', '21', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, ROOT / 'tests/eclipse/EditorSmoke.java', ROOT / 'tests/eclipse/ProjectImportSmoke.java', ROOT / 'tests/eclipse/StandardProjectFlowSmoke.java', ROOT / 'tests/eclipse/WorkbenchSmoke.java', ROOT / 'tests/eclipse/CoreBuildCompatibilitySmoke.java'])
    jar = temporary / 'editor-smoke.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: zew.editor.smoke;singleton:=true\nBundle-Version: 1.0.0\nRequire-Bundle: org.eclipse.equinox.app,org.eclipse.core.runtime,org.eclipse.core.resources,org.eclipse.core.expressions,org.eclipse.cdt.core,org.eclipse.debug.ui,org.eclipse.jface,org.eclipse.ui,org.eclipse.ui.ide,org.eclipse.swt,org.eclipse.launchbar.core,org.eclipse.cdt.debug.core,io.github.niwantha33.zew.core\n\n')
        z.writestr('plugin.xml', '<plugin><extension point="org.eclipse.core.runtime.applications" id="run"><application><run class="zew.smoke.EditorSmoke"/></application></extension><extension point="org.eclipse.core.runtime.applications" id="workbench"><application><run class="zew.smoke.WorkbenchSmoke"/></application></extension></plugin>')
        for path in classes.rglob('*.class'): z.write(path, path.relative_to(classes).as_posix())
    config = eclipse / 'configuration/config.ini'; original = config.read_text()
    modified = '\n'.join(line + ',reference:' + jar.as_uri() + '@4:start' if line.startswith('osgi.bundles=') else line for line in original.splitlines()) + '\n'
    config.write_text(modified)
    try:
        for attempt in range(2):
            # p2 may rewrite config.ini on shutdown; restore the test-only bundle for each process.
            config.write_text(modified)
            run([jdk / 'bin/java', '-jar', launcher, '-nosplash', '-consolelog', '-clean', '-data', temporary / 'workspace', '-application', 'zew.editor.smoke.workbench' if args.native_workbench else 'zew.editor.smoke.run', fixture, 'verify' if attempt else 'apply'])
    finally: config.write_text(original)
