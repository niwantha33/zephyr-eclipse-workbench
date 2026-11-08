#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""CI-only real firmware matrix. Uses the same pinned managed setup/providers as Eclipse.

Not an end-user installer. One fresh environment, sequential targets, a 45-minute
subprocess limit, bounded verified archives, no user keys or hardware access.
"""
import argparse
import base64
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import time
import zipfile

REPO = Path(__file__).resolve().parents[1]
TOOLS = REPO / 'plugins/io.github.niwantha33.zew.ui/tools'
BOOTSTRAP = [
    ('python', 'https://www.python.org/ftp/python/3.13.7/python-3.13.7-embed-amd64.zip', 10922561,
     'f6cca216a359be84797cabb54149ce5e062afb16cc7567eb7fc51cacb2d86b65'),
    ('git', 'https://github.com/git-for-windows/git/releases/download/v2.50.1.windows.1/MinGit-2.50.1-64-bit.zip', 47953778,
     '6f672aebe9e488a246efd6875f9197dbc0d9a40100e218acc3877cba2b206c45'),
]

# CI-only failover for the exact byte-pinned MinGit 2.50.1 release asset.
# This independent HTTPS mirror is never used for end-user installations.
# Size and SHA-256 must match BOOTSTRAP before ZIP inspection or extraction.
GIT_BOOTSTRAP_MIRROR = (
    'https://mirrors.huaweicloud.com/repository/toolkit/git-for-windows/'
    'v2.50.1.windows.1/MinGit-2.50.1-64-bit.zip'
)


def run(args, root, env, timeout=2700, capture=False):
    print('CI command:', *map(str, args[:5]), flush=True)
    result = subprocess.run(list(map(str, args)), cwd=root, env=env, check=True, timeout=timeout,
                            stdin=subprocess.DEVNULL, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout if capture else None


def download_verified_bootstrap(archive, name, url, size, digest, *, attempts=5,
                                pause=time.sleep, fallback_url=None):
    """Download exact CI-pinned bytes, with a mirror only after transport failure.

    Wrong size/hash, invalid archives and nontransient HTTP failures always
    fail closed. A mirror never changes the reviewed version or digest.
    """
    transient_http = {429, 500, 502, 503, 504}
    sources = [url]
    if fallback_url is not None:
        if not fallback_url.startswith('https://'):
            raise ValueError('CI bootstrap mirror must use HTTPS')
        sources.append(fallback_url)
    staging = archive.with_name(archive.name + '.part')
    for index, source in enumerate(sources):
        for attempt in range(1, attempts + 1):
            staging.unlink(missing_ok=True)
            try:
                origin = 'primary' if index == 0 else 'verified-byte mirror'
                print(f'CI pinned bootstrap {name} ({origin}): attempt {attempt}/{attempts}',
                      flush=True)
                request = urllib.request.Request(
                    source, headers={'User-Agent': 'Zephyr-Workbench-CI'})
                total = 0
                sha = hashlib.sha256()
                with urllib.request.urlopen(request, timeout=60) as response, staging.open('wb') as output:
                    while block := response.read(262144):
                        total += len(block)
                        if total > size:
                            raise ValueError('Bootstrap download exceeds reviewed size: ' + name)
                        sha.update(block)
                        output.write(block)
                if total != size or sha.hexdigest() != digest:
                    raise ValueError('Bootstrap integrity mismatch: ' + name)
                staging.replace(archive)
                return
            except (urllib.error.URLError, TimeoutError, ConnectionError,
                    http.client.IncompleteRead) as error:
                if isinstance(error, urllib.error.HTTPError) and error.code not in transient_http:
                    raise
                if attempt == attempts:
                    if index == len(sources) - 1:
                        raise
                    print(f'CI pinned bootstrap {name}: primary transport exhausted; '
                          'trying independently hosted mirror with original SHA-256',
                          flush=True)
                    break
                print(f'CI pinned bootstrap {name}: transient transport failure, '
                      f'retrying ({type(error).__name__})', flush=True)
                pause(min(3 * 2 ** (attempt - 1), 30))
            finally:
                staging.unlink(missing_ok=True)



def bootstrap(root):
    if root.exists():
        raise ValueError('CI matrix requires a fresh directory; existing data was preserved')
    root.mkdir(parents=True)
    (root / 'cache').mkdir()
    (root / '.zew-managed').write_text('first-build-win-x64-v1\n')
    for name, url, size, digest in BOOTSTRAP:
        archive = root / 'cache' / (name + '.zip')
        download_verified_bootstrap(
            archive, name, url, size, digest,
            fallback_url=GIT_BOOTSTRAP_MIRROR if name == 'git' else None)
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


def parse_semantic_check(result):
    """Require an actual JSON report; expose failures without false qualification.

    The semantic checker is expected to return 1 when requested undefined
    Kconfig symbols are correctly reported. A Python traceback can also exit
    1, so the process status alone cannot authenticate the report.
    """
    if result.returncode != 1 or not result.stdout.strip():
        raise ValueError(
            'MSPM0 semantic checker did not return its expected JSON report '
            f'(exit={result.returncode}; stderr={result.stderr.strip()[-1200:]!r}; '
            f'stdout={result.stdout.strip()[-500:]!r})')
    try:
        report = json.loads(result.stdout)
    except (json.JSONDecodeError, ValueError) as error:
        raise ValueError(
            'MSPM0 semantic checker output is not JSON '
            f'(stderr={result.stderr.strip()[-1200:]!r}; '
            f'stdout={result.stdout.strip()[-500:]!r})') from error
    if not isinstance(report, dict) or not isinstance(report.get('images'), list) or not report['images']:
        raise ValueError('MSPM0 semantic checker returned an invalid evidence envelope')
    return report


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
        if label in ('adc-nrf', 'ti'):
            # Compile a real bundled peripheral diagnostic, not only an empty
            # Hello World firmware. It is never flashed by this CI matrix.
            example = 'gpio_usage_demo' if label == 'ti' else 'adc_safe_probe'
            shutil.copytree(REPO / 'examples' / example, app)
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
            # Capture and verify the exact native Java receipt on REAL Zephyr
            # output. Fixture-only tests previously missed post-build false
            # UNVERIFIED states. Uses the core classes compiled by test_core.py.
            java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
            # Windows Java's launcher can lose Unicode (such as Ω) in argv.
            # Transport the exact paths as URL-safe base64 ASCII; the Java
            # probe decodes the original UTF-8 before opening any files.
            encode_arg = lambda path: base64.urlsafe_b64encode(
                str(path).encode('utf-8')).decode('ascii')
            run([java, '-cp', REPO / 'build-core-tests',
                 'ConfigurationEvidenceRealBuildProbe',
                 encode_arg(app), encode_arg(build_arg), target, 'debug'],
                workspace, env)
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
                payload = parse_semantic_check(semantic)
                semantic_status = payload['images'][0]['semantic']
                if semantic_status['state'] != 'evaluated':
                    # This is a bounded reason code, never arbitrary Kconfig
                    # source text or local paths. Keep the qualification strict.
                    raise ValueError('Real Kconfiglib semantic environment unavailable: '
                                     + str(semantic_status.get('reason', 'missing-reason'))[:100])
                by_name = {item['symbol']: item['kconfiglib']
                           for item in payload['images'][0]['requests']}
                if (by_name['CONFIG_GPIO']['state'] != 'defined-in-selected-tree'
                        or by_name['CONFIG_ZEW_NONEXISTENT_SEMANTIC_CI_20261030']['state']
                        != 'undefined-in-selected-tree'):
                    raise ValueError('Actual Zephyr symbol existence evaluation failed')
                if hashlib.sha256(kconfig_source.read_bytes()).digest() != before_inspection:
                    raise ValueError('Kconfig semantic inspector modified generated .config')
                print('PASS: real MSPM0 Kconfiglib defined/undefined symbol check')
                # Read-only edtlib smoke on a REAL generated Zephyr board tree.
                # Root '/' may have no binding; that is incomplete evidence,
                # NOT a failed parser and not proof of a peripheral's presence.
                generated_dts = Path(build_arg) / 'zephyr/zephyr.dts'
                dts_hash = hashlib.sha256(generated_dts.read_bytes()).hexdigest()
                dts_probe = subprocess.run([
                    str(python), str(REPO / 'zew.py'), 'dt-check',
                    '--build', str(build_arg), '--zephyr',
                    str(Path(workspace_arg) / 'zephyr'), '--node', '/'
                ], cwd=REPO, env=env, text=True, encoding='utf-8',
                   errors='replace', capture_output=True, timeout=180)
                try:
                    dts_result = json.loads(dts_probe.stdout)
                except (ValueError, TypeError) as error:
                    raise ValueError('Real Zephyr edtlib did not return JSON') from error
                if dts_probe.returncode not in (0, 1) or dts_result.get('status') not in (
                        'interpreted-preview', 'partial-or-unavailable'):
                    raise ValueError('Real Zephyr edtlib returned invalid status')
                image_state = dts_result['images'][0]['state']
                if image_state not in ('built-tree-evaluated', 'built-tree-incomplete'):
                    raise ValueError('Real generated DTS was not parsed by edtlib: ' + image_state)
                if dts_hash != hashlib.sha256(generated_dts.read_bytes()).hexdigest():
                    raise ValueError('Read-only edtlib inspection changed generated DTS')
                print('PASS: real MSPM0 edtlib generated-tree interpretation')
                # Exercise the SAME Python bridge packaged in the native
                # Configuration Studio, not only the headless evidence CLI.
                ui_probe = subprocess.run([
                    str(python), str(REPO /
                        'plugins/io.github.niwantha33.zew.ui/scripts/dt_binding_inspect.py'),
                    '--build', str(build_arg),
                    '--zephyr', str(Path(workspace_arg) / 'zephyr'),
                    '--application', str(app), '--node', '/'
                ], cwd=REPO, env=env, text=True, encoding='utf-8',
                   errors='replace', capture_output=True, timeout=180)
                lines = ui_probe.stdout.strip().splitlines()
                if len(lines) != 2 or lines[0] != 'ZEW-DTBIND-1':
                    raise ValueError('Native EDT UI bridge returned no valid protocol')
                try:
                    native_fields = [base64.b64decode(word, validate=True).decode('utf-8')
                                     for word in lines[1].split('\t')]
                except (ValueError, UnicodeError) as error:
                    raise ValueError('Native EDT UI protocol invalid') from error
                if (len(native_fields) != 9 or native_fields[0] not in ('interpreted', 'incomplete')
                        or native_fields[1] != dts_hash
                        or native_fields[2] != 'present'
                        or ui_probe.returncode != (0 if native_fields[0] == 'interpreted' else 1)):
                    raise ValueError('Native EDT UI bridge did not inspect real MSPM0 generated tree')
                if hashlib.sha256(generated_dts.read_bytes()).hexdigest() != dts_hash:
                    raise ValueError('Native EDT UI bridge changed generated DTS')
                print('PASS: native SWT resource EDT probe against real MSPM0')
                # Exercise a REAL isolated west/CMake overlay configuration.
                # Candidate is an extra overlay intentionally applied after
                # normal board/application overlays; it never flashes target.
                real_overlay = app / 'zew-ci-extra.overlay'
                real_overlay.write_text(
                    '&{/} { zew-ci-scratch-overlay-marker = "checked"; };\n',
                    encoding='utf-8')
                scratch_parent = root / 'dt-scratch-parent'
                scratch_parent.mkdir(exist_ok=True)
                try:
                    run_check = subprocess.run([
                        str(python), str(REPO / 'zew.py'), 'dt-configure',
                        '--build', str(build_arg), '--application', str(app),
                        '--zephyr', str(Path(workspace_arg) / 'zephyr'),
                        '--python', str(python), '--board', str(target),
                        '--overlay', str(real_overlay), '--ninja', str(ninja),
                        '--scratch-parent', str(scratch_parent), '--timeout', '300',
                        '--expect-node', '/', '--expect-property', 'zew-ci-scratch-overlay-marker'
                    ], cwd=REPO, env=env, text=True, encoding='utf-8',
                       errors='replace', capture_output=True, timeout=390)
                    try:
                        real_report = json.loads(run_check.stdout)
                    except (ValueError, TypeError) as error:
                        raise ValueError('Real isolated west/CMake validation returned no JSON: '
                                         + run_check.stderr[-800:]) from error
                    if (run_check.returncode != 0
                            or real_report.get('status') != 'configured-in-scratch'
                            or real_report.get('evidence_level') != 'configured'
                            or real_report['overlay']['additional'] is not True
                            or real_report['scratch_generated_dts_sha256'] is None
                            or real_report['generated_observation']['state'] != 'present'):
                        raise ValueError('Real MSPM0 CMake overlay configuration failed: '
                                         + str(real_report.get('diagnostic', {}))[:400])
                    if hashlib.sha256(generated_dts.read_bytes()).hexdigest() != dts_hash:
                        raise ValueError('Scratch overlay configuration mutated the selected build')
                    if (real_report['overlay']['sha256'] !=
                            hashlib.sha256(real_overlay.read_bytes()).hexdigest()):
                        raise ValueError('Scratch overlay source hash not preserved')
                    if list(scratch_parent.iterdir()):
                        raise ValueError('Real isolated configuration leaked scratch files')
                    print('PASS: real MSPM0 Zephyr isolated CMake accepted extra overlay')
                    # The native Eclipse packaged adapter must independently
                    # pass against the real MSPM0 CMake tree and observe the
                    # candidate marker; no success from a mocked parser.
                    resource_probe = subprocess.run([
                        str(python), str(REPO /
                            'plugins/io.github.niwantha33.zew.ui/scripts/dt_real_configure_inspect.py'),
                        '--build', str(build_arg), '--application', str(app),
                        '--zephyr', str(Path(workspace_arg) / 'zephyr'),
                        '--board', str(target), '--node', '/',
                        '--property', 'zew-ci-scratch-overlay-marker',
                        '--overlay', str(real_overlay), '--ninja', str(ninja)
                    ], cwd=REPO, env=env, text=True, encoding='utf-8',
                       errors='replace', capture_output=True, timeout=390)
                    lines = resource_probe.stdout.strip().splitlines()
                    if len(lines) != 2 or lines[0] != 'ZEW-REAL-DT-1':
                        raise ValueError('Native real Zephyr configure resource emitted invalid protocol')
                    try:
                        fields = [base64.b64decode(word, validate=True).decode('ascii')
                                  for word in lines[1].split('\t')]
                    except (ValueError, UnicodeError) as error:
                        raise ValueError('Native real Zephyr configure protocol was invalid') from error
                    if (resource_probe.returncode != 0 or len(fields) != 11
                            or fields[0] != 'configured'
                            or fields[1] != 'real-cmake-configured'
                            or fields[2] != dts_hash
                            or fields[3] == '0' * 64
                            or fields[4:6] != ['observed', 'observed']
                            or fields[6] != 'additional'
                            or fields[7] != hashlib.sha256(real_overlay.read_bytes()).hexdigest()):
                        raise ValueError('Native SWT real configure did not verify MSPM0 overlay evidence')
                    if hashlib.sha256(generated_dts.read_bytes()).hexdigest() != dts_hash:
                        raise ValueError('Native real configure modified the selected build DTS')
                    print('PASS: native SWT real Zephyr configure resource verified built MSPM0 DTS')
                finally:
                    real_overlay.unlink(missing_ok=True)
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
        # Independent, read-only selected-peripheral evidence from REAL Zephyr
        # EDT and .config, across every explicit board/SoC/core scenario.
        # Selecting '/' tests provenance without pretending an arbitrary
        # GPIO/ADC controller exists or guessing its compatible binding.
        observed = subprocess.run([
            str(python), str(REPO / 'zew.py'), 'peripheral',
            '--build', str(build_arg), '--zephyr',
            str(Path(workspace_arg) / 'zephyr'), '--node', '/'
        ], cwd=REPO, env=env, capture_output=True, text=True,
           encoding='utf-8', errors='replace', timeout=180)
        try:
            peripheral = json.loads(observed.stdout)
        except (ValueError, TypeError) as error:
            raise ValueError('Cross-vendor peripheral evidence emitted invalid JSON') from error
        if (observed.returncode not in (0, 1)
                or peripheral['status'] not in
                    ('selected-observations-present', 'incomplete-or-unavailable')
                or len(peripheral['images']) != 1
                or peripheral['images'][0]['generated_config_sha256'] !=
                    hashlib.sha256((Path(build_arg) / 'zephyr/.config').read_bytes()).hexdigest()
                or peripheral['images'][0]['generated_dts_sha256'] !=
                    hashlib.sha256((Path(build_arg) / 'zephyr/zephyr.dts').read_bytes()).hexdigest()
                or peripheral['images'][0]['kconfig']['causal_link_to_node'] != 'unverified'
                or 'pin is free' not in ' '.join(peripheral['limitations'])):
            raise ValueError('Cross-vendor peripheral evidence provenance or truth contract failed')
        if observed.returncode == 0 and any(
                item['state'] != 'confirmed'
                for item in peripheral['images'][0]['checks'][:4]):
            raise ValueError('Successful peripheral selected observations were not supported by EDT')
        print('PASS: real generated EDT and Kconfig independent peripheral evidence, ' + label)
        # The guided overlay proposal consumes real configured DTS / EDT but
        # cannot invent a bound GPIO, ADC or physical pin. Selecting '/' is
        # intentionally conservative: a missing binding must return manual
        # review instead of claiming it can enable the root node.
        proposed = subprocess.run([
            str(python), str(REPO / 'zew.py'), 'peripheral-guide',
            '--build', str(build_arg), '--zephyr',
            str(Path(workspace_arg) / 'zephyr'), '--node', '/',
            '--status', 'disabled', '--kind', 'gpio'
        ], cwd=REPO, env=env, capture_output=True, text=True,
           encoding='utf-8', errors='replace', timeout=180)
        try:
            plan = json.loads(proposed.stdout)
        except (ValueError, TypeError) as error:
            raise ValueError('Real Zephyr peripheral-guide emitted invalid JSON') from error
        if (proposed.returncode not in (0, 1)
                or plan['status'] not in
                   ('draft-requires-real-configuration', 'manual-review-required')
                or plan['generated_dts_sha256'] !=
                   hashlib.sha256((Path(build_arg) / 'zephyr/zephyr.dts').read_bytes()).hexdigest()
                or plan['request_type'] != 'status'
                or plan['proposal']['visible_diff'] is not None
                or plan['source']['confidence'] not in ('confirmed', 'unknown')
                or plan['requested_peripheral_kind'] != 'gpio'):
            raise ValueError('Real configured Zephyr guided-source provenance or no-guess contract failed')
        if (plan['proposal']['available'] and
                (plan['status'] != 'draft-requires-real-configuration'
                 or proposed.returncode != 0)):
            raise ValueError('Peripheral guide source was claimed ready without matching EDT')
        print('PASS: real Zephyr binding-grounded source draft contract, ' + label)
        if label == 'ti':
            # Positive *real* Zephyr binding witness: select a GPIO
            # controller only when upstream edtlib verifies the property
            # and binding in this exact generated DTS. No guessed pad.
            witness_code = (
                'import sys\n'
                'from pathlib import Path\n'
                'zephyr=Path(sys.argv[2])\n'
                'sys.path.insert(0,str(zephyr/"scripts/dts/python-devicetree/src"))\n'
                'from devicetree import edtlib\n'
                'edt=edtlib.EDT(sys.argv[1],[str(zephyr/"dts/bindings")])\n'
                'paths=sorted(n.path for n in edt.nodes if n.binding_path '
                'and "gpio-controller" in n.props and n.path.startswith("/"))\n'
                'print(paths[0] if paths else "")\n'
            )
            witness = subprocess.run([
                str(python), '-c', witness_code, str(Path(build_arg) / 'zephyr/zephyr.dts'),
                str(Path(workspace_arg) / 'zephyr')
            ], cwd=REPO, env=env, capture_output=True, text=True,
               encoding='utf-8', errors='replace', timeout=180)
            if witness.returncode != 0 or not witness.stdout.strip().startswith('/'):
                raise ValueError('No genuinely bound GPIO controller from selected TI Zephyr DTS/edtlib')
            actual_node = witness.stdout.strip()
            positive = subprocess.run([
                str(python), str(REPO / 'zew.py'), 'peripheral-guide',
                '--build', str(build_arg), '--zephyr',
                str(Path(workspace_arg) / 'zephyr'), '--node', actual_node,
                '--status', 'disabled', '--kind', 'gpio', '--show-diff'
            ], cwd=REPO, env=env, capture_output=True, text=True,
               encoding='utf-8', errors='replace', timeout=180)
            try:
                positive_report = json.loads(positive.stdout)
            except (ValueError, TypeError) as error:
                raise ValueError('Actual GPIO controller guide did not return JSON') from error
            if (positive.returncode != 0
                    or positive_report['status'] != 'draft-requires-real-configuration'
                    or positive_report['source']['binding'] != 'matched'
                    or 'gpio-controller-property' not in
                        positive_report['source']['interface_hints']
                    or not positive_report['proposal']['available']
                    or 'status = "disabled";' not in
                        positive_report['proposal']['visible_diff']
                    or positive_report['generated_dts_sha256'] !=
                        hashlib.sha256((Path(build_arg) / 'zephyr/zephyr.dts').read_bytes()).hexdigest()):
                raise ValueError('Real TI bound GPIO source-guide positive acceptance failed')
            print('PASS: real TI GPIO controller bound by Zephyr edtlib produced review-only source draft')
            # A stronger, UNSAVED guided draft preflight: use the REAL TI
            # GPIO node that upstream edtlib just verified. Stage status
            # only in a disposable app copy, execute genuine Zephyr
            # West/CMake, and require exact dtlib generated string match.
            # "okay" is intentionally non-disruptive for board consumers.
            original_dts_bytes = (Path(build_arg) / 'zephyr/zephyr.dts').read_bytes()
            ci_overlay = app / 'app.overlay'
            before_ci_overlay = (hashlib.sha256(ci_overlay.read_bytes()).hexdigest()
                                 if ci_overlay.is_file() else None)
            scratch_parent = root / 'unsaved-draft-scratch'
            scratch_parent.mkdir(exist_ok=True)
            unsaved = subprocess.run([
                str(python), str(REPO / 'zew.py'), 'dt-draft-configure',
                '--build', str(build_arg), '--application', str(app),
                '--zephyr', str(Path(workspace_arg) / 'zephyr'),
                '--python', str(python), '--board', str(target),
                '--node', actual_node, '--overlay', str(ci_overlay),
                '--status', 'okay', '--kind', 'gpio',
                '--ninja', str(ninja), '--timeout', '300',
                '--scratch-parent', str(scratch_parent)
            ], cwd=REPO, env=env, capture_output=True, text=True,
               encoding='utf-8', errors='replace', timeout=390)
            try:
                checked_draft = json.loads(unsaved.stdout)
            except (ValueError, TypeError) as error:
                raise ValueError('Real TI unsaved guide CMake preflight returned no JSON') from error
            if (unsaved.returncode != 0
                    or checked_draft['status'] != 'configured-in-scratch'
                    or checked_draft['post_generated_value']['state'] != 'exact-value-observed'
                    or checked_draft['real_configure']['generated_observation']['state'] != 'present'
                    or checked_draft['real_configure']['overlay']['draft_staged_in_scratch_only'] is not True
                    or checked_draft['real_configure']['overlay']['prepared_sha256']
                        != checked_draft['guide']['proposed_overlay_sha256']
                    or checked_draft['guide']['generated_dts_sha256']
                        != hashlib.sha256(original_dts_bytes).hexdigest()):
                raise ValueError('Real TI unsaved guided status preflight was not proven: '
                                 + str(checked_draft.get('real_configure', {}).get('diagnostic', {}))[:300])
            if (Path(build_arg) / 'zephyr/zephyr.dts').read_bytes() != original_dts_bytes:
                raise ValueError('Unsaved guide preflight mutated the selected generated DTS')
            if ((hashlib.sha256(ci_overlay.read_bytes()).hexdigest()
                 if ci_overlay.is_file() else None) != before_ci_overlay):
                raise ValueError('Unsaved guide preflight mutated the actual app.overlay')
            if list(scratch_parent.iterdir()):
                raise ValueError('Unsaved guide preflight leaked disposable source/build data')
            print('PASS: real TI guided unsaved source applied in scratch and exact DTS value observed')
            # Native Eclipse source resources MUST give the same factual
            # result as the headless unsaved preflight on this actual TI
            # board / real Zephyr edtlib GPIO controller. No application
            # overlay gets written or flashed by either invocation.
            reviewed = subprocess.run([
                str(python), str(REPO / 'zew.py'), 'peripheral-guide',
                '--build', str(build_arg), '--zephyr',
                str(Path(workspace_arg) / 'zephyr'), '--node', actual_node,
                '--status', 'okay', '--application', str(app),
                '--overlay', str(ci_overlay)
            ], cwd=REPO, env=env, capture_output=True, text=True,
               encoding='utf-8', errors='replace', timeout=180)
            if reviewed.returncode != 0:
                raise ValueError('Real TI selected guide review was not accepted')
            try:
                selected_review = json.loads(reviewed.stdout)
            except (ValueError, TypeError) as error:
                raise ValueError('Selected TI binding review returned invalid JSON') from error
            native_draft = subprocess.run([
                str(python), '-I', '-B', str(REPO /
                    'plugins/io.github.niwantha33.zew.ui/scripts/dt_draft_configure_inspect.py'),
                '--build', str(build_arg), '--application', str(app),
                '--zephyr', str(Path(workspace_arg) / 'zephyr'),
                '--board', str(target), '--node', actual_node,
                '--overlay', str(ci_overlay), '--status', 'okay',
                '--ninja', str(ninja),
                '--expected-dts-sha256', selected_review['generated_dts_sha256'],
                '--expected-overlay-sha256',
                selected_review['overlay']['original_sha256'] or '0' * 64,
                '--expected-addition-sha256',
                selected_review['proposal']['source_addition_sha256']
            ], cwd=REPO, env=env, capture_output=True, text=True,
               encoding='utf-8', errors='replace', timeout=390)
            parts = native_draft.stdout.strip().splitlines()
            if len(parts) != 2 or parts[0] != 'ZEW-DRAFT-CMAKE-1':
                raise ValueError('Installed unsaved CMake adapter did not return versioned protocol')
            try:
                native_values = [base64.b64decode(x, validate=True).decode('ascii')
                                 for x in parts[1].split('\t')]
            except (ValueError, UnicodeError) as error:
                raise ValueError('Invalid native unsaved CMake protocol base64') from error
            if (native_draft.returncode != 0 or len(native_values) != 13
                    or native_values[0:2] != ['configured', 'real-zephyr-cmake-accepted']
                    or native_values[2] != selected_review['generated_dts_sha256']
                    or native_values[4] !=
                        (selected_review['overlay']['original_sha256'] or '0' * 64)
                    or native_values[5] != selected_review['proposal']['post_change_overlay_sha256']
                    or native_values[6] != native_values[5]
                    or native_values[7] != selected_review['node_sha256']
                    or native_values[8] != selected_review['property_sha256']
                    or native_values[9:12] !=
                        ['exact-value-observed', 'staged', 'identity-verified']):
                raise ValueError('Installed native unsaved CMake did not prove exact generated TI GPIO value')
            if (Path(build_arg) / 'zephyr/zephyr.dts').read_bytes() != original_dts_bytes:
                raise ValueError('Native scratch CMake edited original configured DTS')
            if ((hashlib.sha256(ci_overlay.read_bytes()).hexdigest()
                 if ci_overlay.is_file() else None) != before_ci_overlay):
                raise ValueError('Native scratch CMake edited original user app.overlay')
            print('PASS: native unsaved draft scratch CMake equals exact headless real TI GPIO evidence')
            gui_overlay = app / 'app.overlay'
            before_gui_overlay = (hashlib.sha256(gui_overlay.read_bytes()).hexdigest()
                                  if gui_overlay.is_file() else None)
            guided_native = subprocess.run([
                str(python), '-I', '-B',
                str(REPO / 'plugins/io.github.niwantha33.zew.ui/scripts/peripheral_guide_inspect.py'),
                '--build', str(build_arg), '--zephyr',
                str(Path(workspace_arg) / 'zephyr'), '--node', actual_node,
                '--application', str(app), '--overlay', str(gui_overlay),
                '--status', 'disabled'
            ], cwd=REPO, env=env, capture_output=True, text=True,
               encoding='utf-8', errors='replace', timeout=180)
            output_lines = guided_native.stdout.strip().splitlines()
            if len(output_lines) != 2 or output_lines[0] != 'ZEW-GUIDE-1':
                raise ValueError('Native real Zephyr source guide did not emit valid wire header')
            try:
                gui_fields = [base64.b64decode(x, validate=True).decode('utf-8')
                              for x in output_lines[1].split('\t')]
            except (ValueError, UnicodeError) as error:
                raise ValueError('Native Zephyr source guide emitted invalid encoding') from error
            if (guided_native.returncode != 0 or len(gui_fields) != 14
                    or gui_fields[0:2] != ['draft-requires-real-configuration', 'status']
                    or gui_fields[4] != positive_report['generated_dts_sha256']
                    or gui_fields[7:9] != ['matched', 'confirmed']
                    or not gui_fields[12].startswith('--- selected-overlay-before')
                    or 'status = "disabled";' not in gui_fields[13]
                    or gui_fields[5] != (before_gui_overlay or '0' * 64)):
                raise ValueError('Native guided source proposal mismatches actual headless TI binding')
            if ((hashlib.sha256(gui_overlay.read_bytes()).hexdigest()
                 if gui_overlay.is_file() else None) != before_gui_overlay):
                raise ValueError('Native guided draft modified the original application overlay')
            print('PASS: installed guided overlay SWT adapter verified real TI GPIO source diff')
        # Verify installed SWT resource uses the SAME headless EDT/Kconfig
        # observations across all supported target builds, not a second parser.
        native = subprocess.run([
            str(python), '-I', '-B', str(REPO /
                'plugins/io.github.niwantha33.zew.ui/scripts/peripheral_inspect.py'),
            '--build', str(build_arg), '--zephyr',
            str(Path(workspace_arg) / 'zephyr'), '--node', '/'
        ], cwd=REPO, env=env, capture_output=True, text=True,
           encoding='utf-8', errors='replace', timeout=180)
        lines = native.stdout.strip().splitlines()
        if len(lines) != 2 or lines[0] != 'ZEW-PERIPHERAL-1':
            raise ValueError('Packaged native peripheral evidence protocol missing')
        try:
            fields = [base64.b64decode(value, validate=True).decode('ascii')
                      for value in lines[1].split('\t')]
        except (ValueError, UnicodeError) as error:
            raise ValueError('Packaged native peripheral evidence protocol corrupt') from error
        if (len(fields) != 14
                or fields[0] != peripheral['status']
                or native.returncode != observed.returncode
                or fields[1] != peripheral['images'][0]['generated_dts_sha256']
                or fields[2] != peripheral['images'][0]['generated_config_sha256']
                or fields[3] != peripheral['node_path_sha256']
                or fields[4] != '0' * 64
                or fields[5] != peripheral['images'][0]['node']['state']
                or fields[6] != peripheral['images'][0]['node']['binding']
                or fields[7] != peripheral['images'][0]['node']['configured_status']
                or fields[10] != 'not-requested'
                or fields[11:13] != ['no', 'no']):
            raise ValueError('Packaged native peripheral results differ from actual headless Zephyr evidence')
        print('PASS: installed native peripheral resource equals headless evidence, ' + label)
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
