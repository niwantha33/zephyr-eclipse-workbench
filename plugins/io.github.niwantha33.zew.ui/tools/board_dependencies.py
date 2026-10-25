#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Automatically prepare the selected Zephyr board's pinned modules and compiler.

Invoked by the native Eclipse project builder; not a user-operated installer.
Never edit application sources, replace existing SDK toolchains, fetch blobs, or
update a module past the West manifest's immutable revision.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('zew_board_tools', HERE / 'board_tools.py')
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)
setup = legacy.setup
PINNED_ZEPHYR = '684c9e8f32e4373a21098559f748f06915f950c9'
PINNED_SDK = '1.0.1'
BOARD_PATTERN = re.compile(r'^[A-Za-z0-9_][A-Za-z0-9_.@/+\-]{0,255}$')
SHA_PATTERN = re.compile(r'^[a-f0-9]{40}$')
# SoC metadata supplies the chip vendor, independently of the board vendor.
HAL_ALIASES = {'st': 'stm32', 'nordic': 'nordic', 'ti': 'ti',
               'espressif': 'espressif', 'nxp': 'nxp', 'silabs': 'silabs'}
CPU_FAMILIES = (
    (re.compile(r'CPU_CORTEX|\bARM(?:64)?\b|\bARMV[6789]'), 'arm'),
    (re.compile(r'\bRISCV\b|\bRISC_V\b'), 'riscv'),
    (re.compile(r'\bXTENSA\b'), 'xtensa'),
    (re.compile(r'\bX86(?:_64)?\b'), 'x86'),
    (re.compile(r'\bARC(?:64)?\b'), 'arc'),
)


def checked_board(board):
    if not BOARD_PATTERN.fullmatch(board) or '..' in board or '//' in board or board.endswith('/'):
        raise ValueError('Invalid exact Zephyr board qualifier')
    return board


def soc_metadata(zephyr, soc):
    """Locate both the SoC and its CPU series in the checkout's own YAML."""
    import yaml

    def matching_series(value, series=None):
        if isinstance(value, list):
            for item in value:
                result = matching_series(item, series)
                if result is not None:
                    return result
        elif isinstance(value, dict):
            if value.get('name') == soc and series is not None:
                return series
            for key, child in value.items():
                if key == 'series' and isinstance(child, list):
                    for entry in child:
                        result = matching_series(entry, entry.get('name'))
                        if result is not None:
                            return result
                else:
                    result = matching_series(child, series)
                    if result is not None:
                        return result
        return None

    matches = []
    for path in (zephyr / 'soc').rglob('soc.yml'):
        series = matching_series(yaml.safe_load(path.read_text(encoding='utf-8')))
        if series is not None:
            folder = path.parent / series
            matches.append((path.parent, folder if folder.is_dir() else path.parent))
    if len(matches) != 1:
        raise ValueError('Cannot unambiguously resolve SoC ' + soc + ' from the pinned Zephyr tree')
    relative = matches[0][0].relative_to(zephyr / 'soc')
    return relative.parts[0], matches[0][1]


def architecture(vendor, soc, folder, qualifier):
    """Derive compiler from the selected SoC series and exact core qualifier.

    Mixed ARM/RISC-V SoCs must select a core explicitly; an ambiguous or
    unsupported core fails closed instead of silently installing ARM tools.
    """
    chip = soc.lower()
    core = qualifier.lower().split('/')[-1]
    if vendor == 'espressif':
        if chip in ('esp32', 'esp32s2', 'esp32s3'):
            return 'xtensa-espressif_' + chip + '_zephyr-elf'
        if chip.startswith(('esp32c', 'esp32h', 'esp32p')):
            return 'riscv64-zephyr-elf'
    chunks = []
    for source in folder.rglob('Kconfig*'):
        if source.is_file():
            chunks.append(source.read_text(encoding='utf-8', errors='replace'))
    all_text = '\n'.join(chunks)
    flags = set()
    for regex, family in CPU_FAMILIES:
        if regex.search(all_text):
            flags.add(family)
    if re.search(r'\bARM64\b|\bAARCH64\b', all_text):
        flags.add('aarch64')
    if 'aarch64' in flags and 'arm' in flags:
        # Cortex-A/M can coexist in one family; there is no safe universal default.
        raise ValueError('Multiple ARM core widths found: select a supported CPU cluster for ' + qualifier)
    if len(flags) > 1:
        if core in ('cpuflpr', 'lpcore') and 'riscv' in flags:
            return 'riscv64-zephyr-elf'
        if core in ('cpuapp', 'cpunet', 'cpuppr') and 'arm' in flags:
            return 'arm-zephyr-eabi'
        raise ValueError('Mixed architectures in SoC series; cannot determine compiler for ' + qualifier)
    if flags == {'arm'}:
        return 'arm-zephyr-eabi'
    if flags == {'aarch64'}:
        return 'aarch64-zephyr-elf'
    if flags == {'riscv'}:
        return 'riscv64-zephyr-elf'
    if flags == {'x86'}:
        return 'x86_64-zephyr-elf'
    if flags == {'arc'}:
        return 'arc-zephyr-elf'
    if flags == {'xtensa'}:
        # Xtensa toolchains are core-specific, not interchangeable.
        raise ValueError('No uniquely identified Xtensa CPU compiler for ' + qualifier)
    if vendor == 'ti' and chip.startswith('mspm0'):
        return 'arm-zephyr-eabi'
    raise ValueError('CPU architecture cannot be confirmed for ' + soc + '/' + qualifier
                     + '. Application and SDK were preserved.')

def manifest_projects(python, workspace):
    command = [str(python), '-m', 'west', 'list', '--all', '-f', '{name}\t{revision}\t{path}']
    result = subprocess.run(command, cwd=workspace, check=True, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    rows = {}
    for line in result.stdout.splitlines():
        cells = line.split('\t')
        if len(cells) != 3:
            raise ValueError('Unexpected West manifest output (expected project/revision/path)')
        name, revision, path = cells
        if name in rows or not name:
            raise ValueError('Duplicate or invalid West manifest project: ' + name)
        relative = Path(path)
        if relative.is_absolute() or '..' in relative.parts or ':' in path or '\\' in path:
            raise ValueError('Unsafe West manifest module path: ' + name)
        rows[name] = (revision, relative)
    return rows


def modules_for(vendor, compiler, manifest):
    """Plan pinned CPU support before the selected SoC vendor HAL.

    Zephyr 4.2+ uses cmsis_6 for Cortex-M, while the legacy cmsis
    module remains useful for Cortex-A/R and vendor-HAL compatibility.
    Installing both on 32-bit ARM also handles mixed-profile trees
    without guessing from the vendor name.
    """
    names = []
    if compiler == 'arm-zephyr-eabi':
        for name in ('cmsis_6', 'cmsis'):
            if name not in manifest:
                raise ValueError('Required ARM module is missing from pinned West manifest: ' + name)
            names.append(name)
    alias = HAL_ALIASES.get(vendor, vendor)
    found_hal = False
    for candidate in ('hal_' + alias, 'hal_' + vendor):
        if candidate in manifest:
            names.append(candidate)
            found_hal = True
            break
    if vendor in HAL_ALIASES and not found_hal:
        raise ValueError('Selected vendor HAL is missing from West manifest: hal_' + alias)
    return names



def requires_imgtool(vendor, application=None):
    """Detect boards and applications that need MCUboot's image tool.

    Apply the same logic to every SoC. Explicit MCUboot configuration is
    prepared up front; implicit board defaults are detected from Zephyr's
    generated build rules during the configure-only preflight.
    """
    if application is None:
        return False
    app = Path(application)
    if not app.is_dir():
        raise ValueError('Zephyr application directory is missing: ' + str(app))
    configuration = [app / 'prj.conf', app / 'sysbuild.conf']
    configuration += list((app / 'boards').glob('*.conf'))
    configuration += list((app / 'sysbuild').glob('*.conf'))
    enabled = re.compile(
        r'^\s*(?:CONFIG_BOOTLOADER_MCUBOOT|SB_CONFIG_BOOTLOADER_MCUBOOT)\s*=\s*y\s*(?:#.*)?$',
        re.MULTILINE)
    for file in configuration:
        if file.is_file() and enabled.search(file.read_text(encoding='utf-8', errors='replace')):
            return True
    return False


def install_signing_tools(root, workspace, python, manifest):
    """Provision pinned imgtool Python dependencies without replacing old tools."""
    if 'mcuboot' not in manifest:
        raise ValueError('Pinned Zephyr manifest is missing the mcuboot signing project')
    _, relative = manifest['mcuboot']
    imgtool = workspace / relative / 'scripts' / 'imgtool.py'
    if not imgtool.is_file():
        raise ValueError('Pinned MCUboot module does not provide scripts/imgtool.py: ' + str(imgtool))
    lock_path = HERE / 'mcuboot-python-lock.json'
    source = lock_path.read_bytes()
    lock = json.loads(source)
    if lock.get('schema') != 1 or lock.get('python') != '3.13' or lock.get('platform') != 'win_amd64':
        raise ValueError('Unsupported pinned MCUboot signing dependency lock')
    if sys.version_info[:2] != (3, 13):
        raise ValueError('MCUboot signing requires the managed CPython 3.13 runtime')
    packages = lock.get('packages', [])
    expected = {'cbor2', 'cffi', 'click', 'cryptography', 'intelhex', 'pycparser', 'pyyaml'}
    if {p.get('name', '').lower() for p in packages} != expected:
        raise ValueError('Pinned MCUboot signing dependency set is incomplete')
    digest = hashlib.sha256(source).hexdigest()
    destination = root / 'board-python' / 'mcuboot'
    proof = destination / '.zew-ready'
    if proof.is_file():
        if proof.read_text(encoding='utf-8').strip() != digest:
            raise ValueError('Existing MCUboot signing environment has a different pin; preserved: ' + str(destination))
        setup.log('Reusing pinned MCUboot imgtool Python dependencies')
    else:
        if destination.exists() or destination.is_symlink():
            raise ValueError('Incomplete MCUboot signing environment preserved: ' + str(destination))
        cache = root / 'cache'
        cache.mkdir(parents=True, exist_ok=True)
        archives = [setup.fetch(package, cache) for package in packages]
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.zew-imgtool-stage-', dir=destination.parent) as temporary:
            stage = Path(temporary) / 'packages'
            subprocess.run([str(python), '-m', 'pip', 'install', '--no-index', '--no-deps',
                            '--no-build-isolation', '--disable-pip-version-check',
                            '--target', str(stage), *map(str, archives)],
                           check=True, timeout=600, stdin=subprocess.DEVNULL)
            subprocess.run([str(python), '-c',
                            'import sys;sys.path.insert(0,sys.argv[1]);import cbor2,cffi,click,cryptography,intelhex,yaml',
                            str(stage)], check=True, timeout=30, stdin=subprocess.DEVNULL)
            (stage / '.zew-ready').write_text(digest + '\n', encoding='utf-8')
            stage.rename(destination)
    site = root / 'python' / 'Lib' / 'site-packages'
    if not site.is_dir():
        raise ValueError('Managed Python site-packages is missing: ' + str(site))
    mapping = site / 'zew-mcuboot.pth'
    value = str(destination) + '\n'
    if mapping.exists() and mapping.read_text(encoding='utf-8') != value:
        raise ValueError('Conflicting MCUboot Python path mapping preserved: ' + str(mapping))
    mapping.write_text(value, encoding='utf-8')
    subprocess.run([str(python), str(imgtool), '--help'], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                   text=True, timeout=30, stdin=subprocess.DEVNULL)
    setup.log('READY — pinned MCUboot imgtool is runnable: ' + str(imgtool))

def plan(board, workspace, python, application=None):
    checked_board(board)
    zephyr = workspace / 'zephyr'
    revision = subprocess.check_output(['git', '-C', str(zephyr), 'rev-parse', 'HEAD'],
                                       text=True, timeout=30).strip()
    if revision != PINNED_ZEPHYR:
        raise ValueError('Dependency preparation requires the pinned managed Zephyr 4.4.0 checkout')
    spec = importlib.util.spec_from_file_location('zew_hardware_catalog', HERE / 'hardware_catalog.py')
    catalogue = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(catalogue)
    matches = [row for row in catalogue.catalogue(zephyr) if row[6] == board]
    if len(matches) != 1:
        raise ValueError('Board qualifier not found in this Zephyr checkout: ' + board)
    soc = matches[0][2]
    if soc == '-':
        raise ValueError('Selected board has no unique SoC: ' + board)
    vendor, folder = soc_metadata(zephyr, soc)
    compiler = architecture(vendor, soc, folder, board.split('/', 1)[-1])
    assets = json.loads((HERE / 'sdk-toolchains-windows-1.0.1.json').read_text())
    if assets.get('sdk_version') != PINNED_SDK or compiler not in assets['assets']:
        raise ValueError('No reviewed Windows Zephyr SDK compiler for ' + compiler)
    manifest = manifest_projects(python, workspace)
    names = modules_for(vendor, compiler, manifest)
    signing = requires_imgtool(vendor, application)
    if signing:
        if 'mcuboot' not in manifest:
            raise ValueError('MCUboot signing is needed but its pinned West module is unavailable')
        names.append('mcuboot')
    return {'board': board, 'soc': soc, 'vendor': vendor, 'compiler': compiler,
            'signing': signing, 'modules': names, 'manifest': manifest, 'asset': assets['assets'][compiler]}


def git_revision(git, path):
    return subprocess.check_output([str(git), '-C', str(path), 'rev-parse', 'HEAD'],
                                   text=True, timeout=30).strip()


def module_dependencies(path):
    """Read the Zephyr module's declared build dependencies, if any."""
    import yaml
    metadata = path / 'zephyr' / 'module.yml'
    if not metadata.is_file():
        return []
    document = yaml.safe_load(metadata.read_text(encoding='utf-8')) or {}
    if not isinstance(document, dict):
        raise ValueError('Invalid Zephyr module metadata: ' + str(metadata))
    build = document.get('build') or {}
    if not isinstance(build, dict):
        raise ValueError('Invalid build metadata: ' + str(metadata))
    depends = build.get('depends') or []
    if not isinstance(depends, list) or any(not isinstance(item, str) or not item for item in depends):
        raise ValueError('Invalid build.depends list in ' + str(metadata))
    return depends


def prepare_modules(plan_data, workspace, python, git):
    """Install pinned direct and transitive Zephyr module dependencies."""
    pending = list(plan_data['modules'])
    selected = []
    while pending:
        name = pending.pop(0)
        if name in selected:
            continue
        if name not in plan_data['manifest']:
            raise ValueError('Required module not found in pinned West manifest: ' + name)
        revision, relative = plan_data['manifest'][name]
        if not SHA_PATTERN.fullmatch(revision):
            raise ValueError('Selected West module lacks a pinned SHA: ' + name)
        destination = workspace / relative
        if destination.is_symlink():
            raise ValueError('Refusing symlinked module path: ' + str(destination))
        if destination.exists():
            if not (destination / '.git').exists() or git_revision(git, destination) != revision:
                raise ValueError('Existing module differs from pinned West manifest; preserved: ' + str(destination))
            setup.log('Reusing pinned West module: ' + name)
        else:
            setup.log('Installing pinned West module: ' + name)
            subprocess.run([str(python), '-m', 'west', 'update', name], cwd=workspace, check=True,
                           timeout=1200, stdin=subprocess.DEVNULL)
            if not destination.is_dir() or git_revision(git, destination) != revision:
                raise ValueError('West module revision verification failed: ' + name)
        selected.append(name)
        for required in module_dependencies(destination):
            if required not in plan_data['manifest']:
                raise ValueError('Zephyr module ' + name + ' requires unavailable West project: ' + required)
            if required not in selected and required not in pending:
                pending.append(required)
    plan_data['modules'] = selected


def active_projects(python, workspace):
    """Projects Zephyr can actually discover (west list without --all)."""
    result = subprocess.run([str(python), '-m', 'west', 'list', '-f', '{name}'],
                            cwd=workspace, check=True, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    return set(result.stdout.splitlines())


def activate_modules(plan_data, workspace, python):
    """Make pinned, installed dependencies visible to Zephyr's CMake discovery.

    Keep the existing West project filter intact, including intentionally
    disabled unrelated vendors. Add only exact-name activation overrides.
    """
    required = plan_data['modules']
    active = active_projects(python, workspace)
    missing = [name for name in required if name not in active]
    if not missing:
        return
    current = subprocess.run([str(python), '-m', 'west', 'config', 'manifest.project-filter'],
                             cwd=workspace, text=True, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, timeout=30)
    if current.returncode not in (0, 1):
        raise ValueError('Cannot read existing West project filter: ' + current.stderr.strip())
    previous = current.stdout.strip() if current.returncode == 0 else ''
    overrides = ','.join('+' + re.escape(name) for name in missing)
    value = ','.join(filter(None, (previous, overrides)))
    setup.log('Activating required pinned West modules: ' + ', '.join(missing))
    subprocess.run([str(python), '-m', 'west', 'config', '--local',
                    'manifest.project-filter', value],
                   cwd=workspace, check=True, text=True, timeout=30)
    active_after = active_projects(python, workspace)
    still_missing = [name for name in required if name not in active_after]
    if still_missing:
        raise ValueError('Required West modules are installed but inactive in CMake discovery: '
                         + ', '.join(still_missing))


def prepare_compiler(root, sdk, compiler, asset):
    if (sdk / 'sdk_version').read_text().strip() != PINNED_SDK:
        raise ValueError('Managed SDK version differs from reviewed ' + PINNED_SDK)
    destination = sdk / 'gnu' / compiler
    executable = destination / 'bin' / (compiler + '-gcc.exe')
    if executable.is_file():
        legacy.verify(executable)
        setup.log('Reusing verified ' + compiler)
        return
    if destination.exists():
        raise ValueError('Incomplete compiler directory preserved: ' + str(destination))
    meta = dict(asset, url='https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/' + asset['file'])
    if meta['file'] != 'toolchain_gnu_windows-x86_64_' + compiler + '.7z':
        raise ValueError('Compiler asset name does not match target architecture')
    (root / 'cache').mkdir(exist_ok=True)
    archive = setup.fetch(meta, root / 'cache')
    import py7zr
    with tempfile.TemporaryDirectory(prefix='.zew-compiler-stage-', dir=sdk) as temporary:
        stage = Path(temporary)
        with py7zr.SevenZipFile(archive) as source:
            setup.safe_archive_names(source.list())
            source.extractall(stage)
        matches = [p for p in stage.rglob(compiler + '-gcc.exe') if p.parent.name == 'bin' and p.parent.parent.name == compiler]
        if len(matches) != 1:
            raise ValueError('Unexpected Zephyr compiler archive layout for ' + compiler)
        legacy.verify(matches[0])
        installed = matches[0].parent.parent
        (installed / '.zew-toolchain-proof').write_text(meta['sha256'] + '\n')
        destination.parent.mkdir(exist_ok=True)
        installed.rename(destination)
    setup.log('READY — ' + compiler + ' verified')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--workspace', required=True, type=Path)
    parser.add_argument('--git', required=True)
    parser.add_argument('--board', required=True)
    parser.add_argument('--application', type=Path, help='Project configuration used for optional MCUboot signing')
    parser.add_argument('--require-host-tool', choices=('IMGTOOL',),
                        help='Resolved requirement from the generated CMake build rules')
    parser.add_argument('--plan', action='store_true', help='Report plan only, without modifying files')
    args = parser.parse_args(argv)
    root, sdk, workspace = args.root.resolve(), args.sdk.resolve(), args.workspace.resolve()
    details = plan(args.board, workspace, sys.executable, args.application)
    if args.require_host_tool == 'IMGTOOL':
        # This request comes from Zephyr's actual generated build commands,
        # not from a vendor name. Install the pinned module with its host
        # dependencies even when the application does not mention MCUboot.
        if 'mcuboot' not in details['manifest']:
            raise ValueError('Required IMGTOOL has no pinned MCUboot source in this West manifest')
        if 'mcuboot' not in details['modules']:
            details['modules'].append('mcuboot')
        details['signing'] = True
    setup.log('Selected: ' + details['board'] + ' | SoC=' + details['soc'] +
              ' | chip vendor=' + details['vendor'])
    setup.log('Compiler: ' + details['compiler'] + ' | pinned West modules: ' +
              (', '.join(details['modules']) or 'none'))
    if args.plan:
        return 0
    if os.name != 'nt':
        raise ValueError('Automatic compiler preparation supports managed Windows x64. No changes were made.')
    prepare_compiler(root, sdk, details['compiler'], details['asset'])
    prepare_modules(details, workspace, sys.executable, args.git)
    activate_modules(details, workspace, sys.executable)
    if details['signing']:
        install_signing_tools(root, workspace, sys.executable, details['manifest'])
    if details['vendor'] == 'espressif' and details['soc'] == 'esp32s3':
        legacy.install_python_tools(root)
    setup.log('READY — pinned SDK, West revisions, active modules and signing tools verified')
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        setup.log('FAILED — ' + str(exc))
        raise SystemExit(1)
