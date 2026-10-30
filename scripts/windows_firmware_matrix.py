#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""CI-only real firmware matrix. Uses the same pinned managed setup/providers as Eclipse.

Not an end-user installer. One fresh environment, sequential targets, a 45-minute
subprocess limit, bounded verified archives, no user keys or hardware access.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[1]
TOOLS = REPO / 'plugins/io.github.niwantha33.zew.ui/tools'
BOOTSTRAP = [
    ('python', 'https://www.python.org/ftp/python/3.13.7/python-3.13.7-embed-amd64.zip', 10922561,
     'f6cca216a359be84797cabb54149ce5e062afb16cc7567eb7fc51cacb2d86b65'),
    ('git', 'https://github.com/git-for-windows/git/releases/download/v2.50.1.windows.1/MinGit-2.50.1-64-bit.zip', 47953778,
     '6f672aebe9e488a246efd6875f9197dbc0d9a40100e218acc3877cba2b206c45'),
]


def run(args, root, env, timeout=2700, capture=False):
    print('CI command:', *map(str, args[:5]), flush=True)
    result = subprocess.run(list(map(str, args)), cwd=root, env=env, check=True, timeout=timeout,
                            stdin=subprocess.DEVNULL, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout if capture else None


def bootstrap(root):
    if root.exists():
        raise ValueError('CI matrix requires a fresh directory; existing data was preserved')
    root.mkdir(parents=True)
    (root / 'cache').mkdir()
    (root / '.zew-managed').write_text('first-build-win-x64-v1\n')
    for name, url, size, digest in BOOTSTRAP:
        archive = root / 'cache' / (name + '.zip')
        request = urllib.request.Request(url, headers={'User-Agent': 'Zephyr-Workbench-CI'})
        with urllib.request.urlopen(request, timeout=60) as response, archive.open('wb') as output:
            total = 0
            while block := response.read(262144):
                total += len(block)
                if total > size:
                    raise ValueError('Bootstrap download exceeds reviewed size')
                output.write(block)
        if archive.stat().st_size != size or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise ValueError('Bootstrap integrity mismatch: ' + name)
        with zipfile.ZipFile(archive) as source:
            expanded = 0
            for entry in source.infolist():
                path = PurePosixPath(entry.filename)
                expanded += entry.file_size
                if (path.is_absolute() or '..' in path.parts or ':' in entry.filename or '\\' in entry.filename
                        or (entry.external_attr >> 16) & 0o170000 == 0o120000 or expanded > 1024**3):
                    raise ValueError('Unsafe bootstrap archive')
            source.extractall(root / name)
    (root / 'python/python313._pth').write_text('python313.zip\n.\nLib/site-packages\nimport site\n')
    shutil.copytree(TOOLS, root / 'installer')
    return root / 'python/python.exe'


def select_target(rows, board, soc, core=None):
    selected = [r[6] for r in rows if r[3] == board and r[2] == soc and r[4] == 'default'
                and (core is None or r[5].endswith('/' + core))]
    if len(selected) != 1:
        raise ValueError('Pinned board metadata did not uniquely resolve ' + board + '/' + soc + ': ' + repr(selected))
    return selected[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--setup-only', action='store_true')
    parser.add_argument('--run-only', action='store_true')
    args = parser.parse_args()
    if os.name != 'nt':
        raise ValueError('Native Windows x64 is required for this integration test')
    root = args.root.resolve()
    python = root / 'python/python.exe' if args.run_only else bootstrap(root)
    env = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONUNBUFFERED='1', GIT_TERMINAL_PROMPT='0')
    for key in ('PYTHONHOME', 'PYTHONPATH', 'ZEPHYR_BASE', 'ZEPHYR_SDK_INSTALL_DIR', 'ZEPHYR_TOOLCHAIN_VARIANT', 'BOARD', 'CONF_FILE'):
        env.pop(key, None)
    if not args.run_only:
        run([python, '-u', root / 'installer/setup.py', '--root', root,
             '--lock', root / 'installer/setup-lock.json'], root, env)
    if args.setup_only:
        return
    if not (root / '.zew-ready').is_file():
        raise ValueError('CI managed environment is not ready')
    workspace = root / 'workspace'
    ninja = root / 'python/Scripts/ninja.exe'
    env['PATH'] = os.pathsep.join(map(str, [root / 'python', root / 'python/Scripts', root / 'git/cmd', root / 'board-python/esp32s3/Scripts', root / 'board-python/esp32s3/bin', root / 'sdk/hosttools/bin', root / 'sdk/hosttools/usr/bin'])) + os.pathsep + env.get('PATH', '')
    env.update(ZEPHYR_BASE=(workspace / 'zephyr').as_posix(), ZEPHYR_TOOLCHAIN_VARIANT='zephyr',
               ZEPHYR_SDK_INSTALL_DIR=(root / 'sdk').as_posix())
    text = run([python, root / 'installer/hardware_catalog.py', '--zephyr', workspace / 'zephyr'], root, env, capture=True)
    rows = [line.split('\t') for line in text.splitlines() if len(line.split('\t')) == 7]
    targets = [('ti', 'lp_mspm0g3507', 'mspm0g3507', None),
               ('stm32', 'nucleo_f446re', 'stm32f446xx', None),
               ('nordic', 'nrf52840dk', 'nrf52840', None),
               ('adc-nrf', 'nrf52840dk', 'nrf52840', None),
               ('xtensa', 'esp32s3_devkitc', 'esp32s3', 'procpu'),
               ('riscv', 'esp32c3_devkitm', 'esp32c3', None),
               ('mcuboot', 'nrf52840dk', 'nrf52840', None)]
    results = []
    for label, board, soc, core in targets:
        target = select_target(rows, board, soc, core)
        app = root / 'projects' / ('matrix ' + label + ' Ω')
        if label == 'adc-nrf':
            # Compile a real bundled peripheral diagnostic, not only an empty
            # Hello World firmware. It is never flashed by this CI matrix.
            shutil.copytree(REPO / 'examples/adc_safe_probe', app)
        else:
            app.mkdir(parents=True); (app / 'src').mkdir()
            (app / 'src/main.c').write_text('#include <zephyr/kernel.h>\nint main(void) { return 0; }\n')
            (app / 'CMakeLists.txt').write_text('cmake_minimum_required(VERSION 3.20.5)\nfind_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})\nproject(matrix)\ntarget_sources(app PRIVATE src/main.c)\n')
            (app / 'prj.conf').write_text('CONFIG_BOOTLOADER_MCUBOOT=y\nCONFIG_MCUBOOT_GENERATE_UNSIGNED_IMAGE=y\nCONFIG_BUILD_OUTPUT_BIN=y\n' if label == 'mcuboot' else '')
        source_hash = hashlib.sha256((app / 'src/main.c').read_bytes()).hexdigest()
        build = app / '.zew-build/ci'
        protocol = run([python, root / 'installer/build_paths.py', '--root', root, '--application', app, '--build', build,
                        '--workspace', workspace, '--sdk', root / 'sdk'], workspace, env, capture=True)
        cells = protocol.strip().split('\t')
        if cells[0] != 'ZEW_PATHS_1' or len(cells) != 6:
            raise ValueError('Invalid managed path adapter output')
        app_arg, build_arg, workspace_arg, sdk_arg, fresh = [base64.b64decode(cell).decode('utf-8') for cell in cells[1:]]
        env['ZEPHYR_BASE'] = workspace_arg + '/zephyr'
        env['ZEPHYR_SDK_INSTALL_DIR'] = sdk_arg
        verify_argument = base64.b64encode(str(build).encode('utf-8')).decode('ascii')
        prepare = [python, '-u', root / 'installer/board_dependencies.py', '--root', root, '--sdk', root / 'sdk',
                   '--workspace', workspace, '--git', root / 'git/cmd/git.exe', '--board', target, '--application', app]
        run(prepare, workspace, env)
        configure = [python, '-m', 'west', 'build', '--cmake-only', '-b', target, '-d', build_arg, app_arg, '--',
                     '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', '-DCMAKE_MAKE_PROGRAM:FILEPATH=' + ninja.as_posix(),
                     '-DPython3_EXECUTABLE:FILEPATH=' + python.as_posix()]
        run(configure, workspace, env)
        commands = run([ninja, '-C', build_arg, '-t', 'commands'], workspace, env, capture=True)
        if 'IMGTOOL-NOTFOUND' in commands:
            run([*prepare, '--require-host-tool', 'IMGTOOL'], workspace, env)
            run([*configure, '-UIMGTOOL'], workspace, env)
        run([python, '-m', 'west', 'build', '-d', build_arg], workspace, env)
        if label == 'ti':
            # Exercise the actual generated Kconfiglib environment for an
            # MSPM0G3507 Zephyr build. No source or .config writes allowed.
            kconfig_source = Path(build_arg) / 'zephyr/.config'
            before_inspection = hashlib.sha256(kconfig_source.read_bytes()).digest()
            inspection = subprocess.run([str(python), str(REPO /
                    'plugins/io.github.niwantha33.zew.ui/scripts/kconfig_inspect.py'),
                    '--build', str(build_arg), '--ninja', str(ninja), '--symbol', 'GPIO'],
                    cwd=workspace, env=env, text=True, encoding='utf-8',
                    errors='replace', capture_output=True, timeout=60)
            if inspection.returncode:
                raise ValueError('Real MSPM0 Kconfiglib probe failed: '
                                 + inspection.stdout.strip()[-1200:]
                                 + inspection.stderr.strip()[-300:])
            inspection_lines = inspection.stdout.splitlines()
            if len(inspection_lines) < 2 or inspection_lines[0] != 'ZEW-KCONFIG-1':
                raise ValueError('Real Zephyr Kconfiglib inspection did not return evaluated metadata')
            fields = [base64.b64decode(x).decode('utf-8')
                      for x in inspection_lines[1].split('\t')]
            if len(fields) != 12 or fields[0] != 'GPIO' or fields[1] not in ('bool', 'tristate')                     or fields[3] not in ('n', 'm', 'y'):
                raise ValueError('Real MSPM0 Kconfiglib symbol metadata is invalid')
            if hashlib.sha256(kconfig_source.read_bytes()).digest() != before_inspection:
                raise ValueError('Read-only Kconfiglib inspector changed generated .config')
            print('PASS: MSPM0 generated Kconfiglib environment evaluated without source edits')
            # Exercise the headless learning checker against this REAL Zephyr
            # Kconfig tree. This confirms that an arbitrary suggested symbol
            # can be distinguished from actual definitions without code edits.
            semantic_snippet = Path(build_arg) / '.zew-semantic-ci.conf'
            semantic_snippet.write_text(
                'CONFIG_GPIO=y\nCONFIG_ZEW_NONEXISTENT_SEMANTIC_CI_20261030=y\n',
                encoding='utf-8')
            try:
                semantic = subprocess.run(
                    [str(python), str(REPO / 'zew.py'), 'config-check',
                     '--build', str(build_arg), '--snippet', str(semantic_snippet),
                     '--semantic', '--ninja', str(ninja)],
                    cwd=REPO, env=env, text=True, encoding='utf-8',
                    errors='replace', capture_output=True, timeout=120)
                if semantic.returncode != 1:
                    raise ValueError('Real Kconfiglib semantic checker exit contract failed')
                payload = json.loads(semantic.stdout)
                if payload['images'][0]['semantic']['state'] != 'evaluated':
                    raise ValueError('Real Kconfiglib semantic environment unavailable')
                by_name = {item['symbol']: item['kconfiglib']
                           for item in payload['images'][0]['requests']}
                if (by_name['CONFIG_GPIO']['state'] != 'defined-in-selected-tree'
                        or by_name['CONFIG_ZEW_NONEXISTENT_SEMANTIC_CI_20261030']['state']
                        != 'undefined-in-selected-tree'):
                    raise ValueError('Actual Zephyr symbol existence evaluation failed')
                if hashlib.sha256(kconfig_source.read_bytes()).digest() != before_inspection:
                    raise ValueError('Kconfig semantic inspector modified generated .config')
                print('PASS: real MSPM0 Kconfiglib defined/undefined symbol check')
            finally:
                semantic_snippet.unlink(missing_ok=True)
        # Shared Java verifier is compiled by scripts/test_core.py in this job.
        run(['java', '-cp', REPO / 'build-core-tests', 'FirmwareVerify', '--base64', verify_argument], workspace, env)
        run([python, root / 'installer/editor_settings.py', '--project', app, '--build', build,
             '--sdk', root / 'sdk', '--output', build / 'editor.tsv'], workspace, env)
        if label == 'mcuboot' and not (build / 'zephyr/zephyr.signed.bin').is_file():
            raise ValueError('Non-Espressif MCUboot image was not generated')
        state = sorted((root / '.zew-dependency-state').glob('*.json'))
        fingerprint = {str(p):p.read_bytes() for p in state}
        run(prepare, workspace, env)
        run([python, '-m', 'west', 'build', '-d', build_arg], workspace, env)
        if any(Path(p).read_bytes() != data for p, data in fingerprint.items()):
            raise ValueError('Second build changed verified dependency provenance')
        if label == 'ti':
            run([python, '-m', 'west', 'build', '-d', build_arg, '-t', 'clean'], workspace, env)
            run([python, '-m', 'west', 'build', '-d', build_arg], workspace, env)
            run(['java', '-cp', REPO / 'build-core-tests', 'FirmwareVerify', '--base64', verify_argument], workspace, env)
        if label == 'ti':
            # Real compiler failure must stay a compiler failure. No provider
            # is run in this phase, and source/evidence are restored afterwards.
            original_source = (app / 'src/main.c').read_bytes()
            try:
                (app / 'src/main.c').write_bytes(original_source + b'\n#error ZEW_EXPECTED_SOURCE_FAILURE\n')
                failed = subprocess.run([str(python), '-m', 'west', 'build', '-d', build_arg],
                                        cwd=workspace, env=env, timeout=300, stdin=subprocess.DEVNULL)
                if failed.returncode == 0:
                    raise ValueError('Compiler error incorrectly reported as success')
            finally:
                (app / 'src/main.c').write_bytes(original_source)
            if any(Path(p).read_bytes() != data for p, data in fingerprint.items()):
                raise ValueError('Source compilation failure mutated dependencies')
            pristine = [item for item in configure if item != '--cmake-only']
            pristine.insert(4, '--pristine=always')
            run(pristine, workspace, env)
            run(['java', '-cp', REPO / 'build-core-tests', 'FirmwareVerify', '--base64', verify_argument], workspace, env)
        if hashlib.sha256((app / 'src/main.c').read_bytes()).hexdigest() != source_hash:
            raise ValueError('Application source changed')
        # Every real vendor build is now independently inspected by the
        # Evidence Engine. This is build-artifact evidence, NEVER a flash or
        # physical-board assertion. Run after deliberate failure recovery.
        evidence_raw = run([python, REPO / 'scripts/evidence_firmware_probe.py',
                            '--build', build_arg, '--application', app],
                           workspace, env, capture=True, timeout=180)
        evidence = json.loads(evidence_raw)
        if evidence['claim_limit'].find('no runtime or flashed-device') < 0:
            raise ValueError('Evidence report overstated verification scope')
        results.append(dict(target=target, scenario=label, firmware_build_verified=True,
                            editor_metadata_verified=True, evidence_engine=evidence))
        (REPO / 'windows-firmware-results.json').write_text(json.dumps(results, indent=2))
    print(json.dumps(results, indent=2))


if __name__ == '__main__': main()
