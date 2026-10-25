#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""CI-only real firmware matrix. Uses the same pinned managed setup/providers as Eclipse.

Not an end-user installer. One fresh environment, sequential targets, a 45-minute
subprocess limit, bounded verified archives, no user keys or hardware access.
"""
import argparse
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
    env['PATH'] = os.pathsep.join(map(str, [root / 'python', root / 'python/Scripts', root / 'git/cmd'])) + os.pathsep + env.get('PATH', '')
    env.update(ZEPHYR_BASE=(workspace / 'zephyr').as_posix(), ZEPHYR_TOOLCHAIN_VARIANT='zephyr',
               ZEPHYR_SDK_INSTALL_DIR=(root / 'sdk').as_posix())
    text = run([python, root / 'installer/hardware_catalog.py', '--zephyr', workspace / 'zephyr'], root, env, capture=True)
    rows = [line.split('\t') for line in text.splitlines() if len(line.split('\t')) == 7]
    targets = [('ti', 'lp_mspm0g3507', None), ('stm32', 'nucleo_f446re', None),
               ('nordic', 'nrf52840dk', None), ('xtensa', 'esp32s3_devkitc', 'procpu'),
               ('riscv', 'esp32c3_devkitm', None), ('mcuboot', 'nrf52840dk', None)]
    results = []
    for label, board, core in targets:
        selected = [r[6] for r in rows if r[3] == board and r[4] == 'default'
                    and (core is None or r[5].endswith('/' + core))]
        if len(selected) != 1:
            raise ValueError('Pinned board metadata did not uniquely resolve ' + board + ': ' + repr(selected))
        target = selected[0]
        app = root / 'projects' / ('matrix ' + label + ' Ω')
        app.mkdir(parents=True); (app / 'src').mkdir()
        (app / 'src/main.c').write_text('#include <zephyr/kernel.h>\nint main(void) { return 0; }\n')
        (app / 'CMakeLists.txt').write_text('cmake_minimum_required(VERSION 3.20.5)\nfind_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})\nproject(matrix)\ntarget_sources(app PRIVATE src/main.c)\n')
        (app / 'prj.conf').write_text('CONFIG_BOOTLOADER_MCUBOOT=y\nCONFIG_MCUBOOT_GENERATE_UNSIGNED_IMAGE=y\nCONFIG_BUILD_OUTPUT_BIN=y\n' if label == 'mcuboot' else '')
        source_hash = hashlib.sha256((app / 'src/main.c').read_bytes()).hexdigest()
        build = app / '.zew-build/ci'
        prepare = [python, '-u', root / 'installer/board_dependencies.py', '--root', root, '--sdk', root / 'sdk',
                   '--workspace', workspace, '--git', root / 'git/cmd/git.exe', '--board', target, '--application', app]
        run(prepare, workspace, env)
        configure = [python, '-m', 'west', 'build', '--cmake-only', '-b', target, '-d', build.as_posix(), app.as_posix(), '--',
                     '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', '-DCMAKE_MAKE_PROGRAM:FILEPATH=' + ninja.as_posix(),
                     '-DPython3_EXECUTABLE:FILEPATH=' + python.as_posix()]
        run(configure, workspace, env)
        commands = run([ninja, '-C', build, '-t', 'commands'], workspace, env, capture=True)
        if 'IMGTOOL-NOTFOUND' in commands:
            run([*prepare, '--require-host-tool', 'IMGTOOL'], workspace, env)
            run([*configure, '-UIMGTOOL'], workspace, env)
        run([python, '-m', 'west', 'build', '-d', build.as_posix()], workspace, env)
        # Shared Java verifier is compiled by scripts/test_core.py in this job.
        run(['java', '-cp', REPO / 'build-core-tests', 'FirmwareVerify', build], workspace, env)
        if label == 'mcuboot' and not (build / 'zephyr/zephyr.signed.bin').is_file():
            raise ValueError('Non-Espressif MCUboot image was not generated')
        state = sorted((root / '.zew-dependency-state').glob('*.json'))
        fingerprint = {str(p):p.read_bytes() for p in state}
        run(prepare, workspace, env)
        run([python, '-m', 'west', 'build', '-d', build.as_posix()], workspace, env)
        if any(Path(p).read_bytes() != data for p, data in fingerprint.items()):
            raise ValueError('Second build changed verified dependency provenance')
        if label == 'ti':
            run([python, '-m', 'west', 'build', '-d', build.as_posix(), '-t', 'clean'], workspace, env)
            run([python, '-m', 'west', 'build', '-d', build.as_posix()], workspace, env)
            run(['java', '-cp', REPO / 'build-core-tests', 'FirmwareVerify', build], workspace, env)
        if hashlib.sha256((app / 'src/main.c').read_bytes()).hexdigest() != source_hash:
            raise ValueError('Application source changed')
        results.append(dict(target=target, scenario=label, firmware_build_verified=True))
        (REPO / 'windows-firmware-results.json').write_text(json.dumps(results, indent=2))
    print(json.dumps(results, indent=2))


if __name__ == '__main__': main()
