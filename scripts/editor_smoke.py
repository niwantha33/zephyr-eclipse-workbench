#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Test the installed editor plug-in in a disposable Eclipse containing CDT core.

--eclipse must be a disposable installation: this script installs the workbench
and adds a test-only bundle. No user Eclipse/workspace should be supplied.
The C fixture is compiled/preprocessed by the host GCC before CDT checks it.
"""
import argparse
import socket
import time
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.environ['ZEW_TEST_PYTHON'] = sys.executable
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
    # This is an actual Zephyr application even with no selected board/SDK.
    # The Configuration Studio must open prj.conf without a working build.
    (fixture / 'CMakeLists.txt').write_text(
        'cmake_minimum_required(VERSION 3.20.0)\n'
        'find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})\n'
        'project(zew_editor_smoke)\n'
        'target_sources(app PRIVATE src/main.c)\n')
    (fixture / 'prj.conf').write_text('CONFIG_LOG=y\nCONFIG_DEBUG_INFO=y\n')
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
    metadata_source = temporary / 'thread-metadata.c'
    metadata_source.write_text('const unsigned char _kernel_thread_info_size_t_size = 4;\nconst unsigned int _kernel_thread_info_num_offsets = 15;\nconst unsigned int _kernel_thread_info_offsets[15] = {1,0,4,0};\nint _kernel = 1;\nvoid entry(void) {}\n')
    for bits in (32, 64):
        run([gcc, f'-m{bits}', '-nostdlib', '-static', '-Wl,-e,entry', metadata_source, '-o', fixture / f'thread-{bits}.elf'])
        run([shutil.which('objcopy'), '-O', 'ihex', fixture / f'thread-{bits}.elf', fixture / f'thread-{bits}.hex'])
    if args.native_workbench:
        run([gcc, '-pthread', '-g', '-O0', '-no-pie', ROOT / 'tests/eclipse/remote_threads.c', '-o', fixture / 'remote-threads'])
    classes = temporary / 'classes'; classes.mkdir()
    classpath = os.pathsep.join(map(str, (eclipse / 'plugins').glob('*.jar')))
    run([jdk / 'bin/javac', '--release', '21', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, ROOT / 'tests/eclipse/EditorSmoke.java', ROOT / 'tests/eclipse/ProjectImportSmoke.java', ROOT / 'tests/eclipse/StandardProjectFlowSmoke.java', ROOT / 'tests/eclipse/WorkbenchSmoke.java', ROOT / 'tests/eclipse/CoreBuildCompatibilitySmoke.java', ROOT / 'tests/eclipse/OpenOcdMetadataSmoke.java', ROOT / 'tests/eclipse/RemoteSmokeDelegate.java', ROOT / 'tests/eclipse/RemoteDebugSmoke.java'])
    jar = temporary / 'editor-smoke.jar'
    with zipfile.ZipFile(jar, 'w') as z:
        z.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-SymbolicName: zew.editor.smoke;singleton:=true\nBundle-Version: 1.0.0\nRequire-Bundle: org.eclipse.equinox.app,org.eclipse.core.runtime,org.eclipse.core.resources,org.eclipse.core.expressions,org.eclipse.cdt.core,org.eclipse.debug.ui,org.eclipse.jface,org.eclipse.ui,org.eclipse.ui.ide,org.eclipse.swt,org.eclipse.launchbar.core,org.eclipse.cdt.debug.core,org.eclipse.cdt.dsf,org.eclipse.cdt.dsf.gdb,org.eclipse.debug.core,io.github.niwantha33.zew.core,io.github.niwantha33.zew.ui\n\n')
        z.writestr('plugin.xml', '<plugin><extension point="org.eclipse.debug.core.launchConfigurationTypes"><launchConfigurationType id="zew.editor.smoke.remote" name="Test-only remote GDB" modes="debug" delegate="zew.smoke.RemoteSmokeDelegate" sourceLocatorId="org.eclipse.cdt.debug.core.sourceLocator" sourcePathComputerId="org.eclipse.cdt.debug.core.sourcePathComputer"/></extension><extension point="org.eclipse.core.runtime.applications" id="run"><application><run class="zew.smoke.EditorSmoke"/></application></extension><extension point="org.eclipse.core.runtime.applications" id="workbench"><application><run class="zew.smoke.WorkbenchSmoke"/></application></extension></plugin>')
        for path in classes.rglob('*.class'): z.write(path, path.relative_to(classes).as_posix())
    config = eclipse / 'configuration/config.ini'; original = config.read_text()
    modified = '\n'.join(line + ',reference:' + jar.as_uri() + '@4:start' if line.startswith('osgi.bundles=') else line for line in original.splitlines()) + '\n'
    config.write_text(modified)
    try:
        for attempt in range(2):
            # p2 may rewrite config.ini on shutdown; restore the test-only bundle for each process.
            config.write_text(modified)
            with tempfile.TemporaryDirectory(prefix='zew-gdb-') as debug_directory:
                properties = []
                server = None
                server_output = None
                try:
                    if args.native_workbench:
                        gdb = shutil.which('gdb'); gdbserver = shutil.which('gdbserver')
                        if not gdb or not gdbserver:
                            raise RuntimeError('Native debug regression requires test-only gdb and gdbserver')
                        os.environ['ZEW_REAL_GDB'] = gdb
                        os.environ['ZEW_THREAD_EVENT_LOG'] = str(Path(debug_directory) / 'withheld-events.log')
                        os.environ['ZEW_MI_TRACE_LOG'] = str(Path(debug_directory) / 'gdb-mi-trace.log')
                        wrapper = Path(debug_directory) / 'gdb-relay'
                        shutil.copyfile(ROOT / 'tests/tools/gdb_without_thread_events.py', wrapper)
                        wrapper.chmod(0o755)
                        with socket.socket() as endpoint:
                            endpoint.bind(('127.0.0.1', 0)); port = endpoint.getsockname()[1]
                        server_log = Path(debug_directory) / 'gdbserver.log'
                        server_output = server_log.open('wb')
                        server = subprocess.Popen([gdbserver, '--once', f'127.0.0.1:{port}', str(fixture / 'remote-threads')], stdout=server_output, stderr=subprocess.STDOUT)
                        deadline = time.monotonic() + 10
                        while 'Listening on port' not in server_log.read_text(errors='replace'):
                            if server.poll() is not None or time.monotonic() >= deadline:
                                raise RuntimeError(server_log.read_text(errors='replace'))
                            time.sleep(0.05)
                        properties = [f'-Dzew.remote.gdb={wrapper}', f'-Dzew.remote.port={port}']
                    try:
                        run([jdk / 'bin/java', *properties, '-jar', launcher, '-nosplash', '-consolelog', '-clean', '-data', temporary / 'workspace', '-application', 'zew.editor.smoke.workbench' if args.native_workbench else 'zew.editor.smoke.run', fixture, 'verify' if attempt else 'apply'])
                    except subprocess.CalledProcessError:
                        trace = Path(debug_directory) / 'gdb-mi-trace.log'
                        if trace.is_file():
                            print('=== Test-only GDB MI stack/error trace (last 100 records) ===', file=sys.stderr)
                            for row in trace.read_text(errors='replace').splitlines()[-100:]:
                                print(row[:500], file=sys.stderr)
                        raise
                finally:
                    if server is not None:
                        if server.poll() is None:
                            server.terminate()
                            try: server.wait(timeout=3)
                            except subprocess.TimeoutExpired: server.kill(); server.wait(timeout=3)
                        server_output.close()

    finally: config.write_text(original)
