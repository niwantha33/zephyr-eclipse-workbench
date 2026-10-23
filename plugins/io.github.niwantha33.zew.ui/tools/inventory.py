#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only local inventory; TSV/base64 protocol keeps paths and logs separate.

No package downloads, project builds, workspace changes or vendor blob fetches.
Runs only explicitly selected executables and standard version commands.
"""
import argparse
import base64
import configparser
import importlib.metadata as metadata
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


def emit(kind, *values):
    print(kind + '\t' + '\t'.join(base64.b64encode(str(v).encode('utf-8')).decode('ascii')
                                  for v in values), flush=True)


def version_tuple(text):
    match = re.search(r'(\d+)\.(\d+)(?:\.(\d+))?', text)
    return tuple(int(x or 0) for x in match.groups()) if match else None


def execute(command, cwd=None):
    try:
        p = subprocess.run(list(map(str, command)), cwd=cwd, encoding='utf-8', errors='replace',
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20,
                           stdin=subprocess.DEVNULL, shell=False)
        text = p.stdout.strip()
        return p.returncode, text[:32000]
    except (OSError, subprocess.TimeoutExpired) as error:
        return -1, str(error)


def row(name, required, installed, status, location, action):
    emit('ROW', name, required, installed, status, location, action)


def executable(name, selected=''):
    # An explicit selection must never silently fall back to PATH.
    if selected:
        p = Path(selected).expanduser().resolve()
        return p if p.is_file() else None
    found = shutil.which(name)
    return Path(found).resolve() if found else None


def host_tool(name, selected, minimum=None, extra_dirs=()):
    p = executable(name, selected)
    if not selected:
        suffix = '.exe' if os.name == 'nt' else ''
        for directory in extra_dirs:
            candidate = Path(directory) / (name + suffix)
            if candidate.is_file():
                p = candidate.resolve()
                break
    requirement = '>=' + '.'.join(map(str, minimum)) if minimum else 'Optional; board/operation dependent'
    if p is None:
        row(name, requirement, '', 'Missing' if minimum else 'Optional', selected,
            'Select an installed executable; download installer is not enabled in this release')
        return
    code, text = execute([p, '--version'])
    value = version_tuple(text)
    ready = code == 0 and value is not None and (minimum is None or value >= minimum)
    row(name, requirement, text.splitlines()[0] if text else 'No version output',
        'Ready' if ready else 'Incompatible / failed', p,
        'Reuse selected executable' if ready else 'Select a compatible installation')
    if ready:
        emit('BIND', name, p)


def find_zephyr(workspace):
    if not (workspace / '.west/config').is_file():
        return None, 'No .west/config; select an initialized West workspace'
    code, text = execute([sys.executable, '-m', 'west', 'list', 'zephyr', '--format', '{abspath}'], workspace)
    if code == 0 and len(text.splitlines()) == 1:
        p = Path(text).resolve()
        if (p / 'VERSION').is_file():
            return p, 'West project metadata'
    # Read-only fallback for environments where West is not yet installed.
    candidates = [workspace / 'zephyr', workspace / 'external/zephyr']
    matches = [p.resolve() for p in candidates if (p / 'VERSION').is_file()]
    return (matches[0], 'Conventional source location; West verification unavailable') if len(matches) == 1 else (None, text or 'Zephyr source not found')


def zephyr_version(source):
    values = dict(re.findall(r'^(VERSION_MAJOR|VERSION_MINOR|PATCHLEVEL)\s*=\s*(\d+)',
                             (source / 'VERSION').read_text(), re.M))
    return '.'.join(values.get(k, '?') for k in ('VERSION_MAJOR', 'VERSION_MINOR', 'PATCHLEVEL'))


def required_version(source, filename, pattern, fallback):
    if source:
        p = source / filename
        if p.is_file():
            m = re.search(pattern, p.read_text(errors='replace'))
            if m:
                return version_tuple(m.group(1))
    return fallback


def sdk_candidates(selected, home=None):
    if selected:
        return [Path(selected).expanduser().resolve()]
    candidates = []
    if os.environ.get('ZEPHYR_SDK_INSTALL_DIR'):
        candidates.append(Path(os.environ['ZEPHYR_SDK_INSTALL_DIR']))
    home = Path.home() if home is None else home
    for directory in (home, home / '.local', home / '.local/opt', home / 'tools', Path('/opt')):
        if directory.is_dir():
            candidates.extend(sorted(directory.glob('zephyr-sdk-*'), reverse=True))
    if os.name == 'nt':
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Kitware\CMake\Packages\Zephyr-sdk') as key:
                for i in range(winreg.QueryInfoKey(key)[1]):
                    location = Path(winreg.EnumValue(key, i)[1])
                    # Registry points at the SDK's cmake package directory.
                    for p in (location, *list(location.parents)[:3]):
                        if (p / 'sdk_version').is_file():
                            candidates.append(p)
        except OSError:
            pass
    return list(dict.fromkeys(p.resolve() for p in candidates if p.is_dir()))


def scan_sdk(selected, recommended):
    candidates = sdk_candidates(selected)
    valid = [p for p in candidates if (p / 'sdk_version').is_file()]
    if not valid:
        row('Zephyr SDK', 'Checkout recommends ' + recommended, '', 'Missing', selected,
            'Browse to an existing SDK; automatic SDK download follows in a later release')
        return []
    # Prefer the checkout's recommendation; never pick the lexicographically largest version.
    matching = [p for p in valid if (p / 'sdk_version').read_text().strip() == recommended]
    sdk = (matching or valid)[0]
    version = (sdk / 'sdk_version').read_text().strip()
    row('Zephyr SDK', 'Checkout recommends ' + recommended, version, 'Detected; build validates compatibility', sdk,
        'Reuse SDK; installed toolchains listed below')
    emit('BIND', 'sdk', sdk)
    base = sdk / 'gnu' if (sdk / 'gnu').is_dir() else sdk
    found = 0
    for directory in sorted(base.iterdir()):
        if not directory.is_dir() or not re.fullmatch(r'[A-Za-z0-9_-]+', directory.name):
            continue
        suffix = '.exe' if os.name == 'nt' else ''
        gcc = directory / 'bin' / (directory.name + '-gcc' + suffix)
        if not gcc.is_file():
            continue
        found += 1
        code, text = execute([gcc, '--version'])
        row(directory.name, 'Select exact architecture for the board', text.splitlines()[0] if text else '',
            'Ready' if code == 0 and version_tuple(text) else 'Failed', gcc,
            'Present; the actual build selects and verifies the compiler')
    if not found:
        row('SDK GNU toolchains', 'At least the selected board architecture', '', 'Missing', sdk,
            'Minimal SDK found; no GCC toolchains installed')
    # Hosttools layout differs before SDK 1.0.
    return [p for root in (sdk / 'hosttools', sdk / 'sysroots') if root.is_dir()
            for p in [root / 'bin', *root.glob('*/usr/bin')] if p.is_dir()]


def check_requirements(source):
    """Evaluate declared base build requirements locally, including markers/includes."""
    managed_lock = Path(sys.prefix).parent / 'installer/setup-lock.json'
    if (Path(sys.prefix) / '.zew-managed-python').is_file() and managed_lock.is_file():
        import json
        try:
            lock = json.loads(managed_lock.read_text(encoding='utf-8'))
            missing = []
            for package in lock['packages']:
                try:
                    if metadata.version(package['name']) != package['version']:
                        missing.append(package['name'] + ' version differs')
                except metadata.PackageNotFoundError:
                    missing.append(package['name'])
            row('Python build packages', 'Managed first-build profile only',
                '; '.join(missing) if missing else 'Pinned versions satisfied',
                'Missing / incompatible' if missing else 'Ready', managed_lock,
                'Additional board/runner features require their own setup profile')
        except (ValueError, KeyError, OSError) as error:
            row('Python build packages', 'Managed first-build profile', str(error), 'Not verified', managed_lock, 'Retry Set Up Zephyr')
        return
    try:
        from packaging.requirements import Requirement
    except ImportError:
        row('Python build packages', 'scripts/requirements-base.txt', '', 'Not verified', sys.prefix,
            'packaging is missing; package download/repair is not enabled yet')
        return
    start = source / 'scripts/requirements-base.txt'
    if not start.is_file():
        row('Python build packages', 'Checkout base requirements', '', 'Not verified', source,
            'Base requirements file not found')
        return
    missing = []
    visited = set()

    def visit(path):
        path = path.resolve()
        if path in visited:
            return
        if not path.is_relative_to(source.resolve()):
            raise ValueError('Requirements include leaves selected source tree')
        visited.add(path)
        for raw in path.read_text().splitlines():
            text = raw.split(' #', 1)[0].strip()
            if not text or text.startswith('#'):
                continue
            if text.startswith('-r '):
                visit(path.parent / text[3:].strip())
                continue
            requirement = Requirement(text)
            if requirement.url:
                missing.append(requirement.name + ': URL requirement needs review')
                continue
            if requirement.marker and not requirement.marker.evaluate():
                continue
            try:
                installed = metadata.version(requirement.name)
                if not requirement.specifier.contains(installed):
                    missing.append(str(requirement) + ' (installed ' + installed + ')')
            except metadata.PackageNotFoundError:
                missing.append(str(requirement))
    try:
        visit(start)
        row('Python build packages', 'Checkout base requirements (current host markers)',
            'Declared versions satisfied' if not missing else '; '.join(missing),
            'Ready' if not missing else 'Missing / incompatible', start,
            'Base build packages only; optional features and licence eligibility need separate review')
    except (ValueError, OSError) as error:
        row('Python build packages', 'Checkout base requirements', str(error), 'Not verified', start,
            'Resolve unsupported requirements before installing')


def inventory(args):
    workspace = Path(args.workspace).expanduser().resolve()
    emit('PHASE', 'Locating Zephyr workspace')
    source, reason = find_zephyr(workspace) if workspace.is_dir() else (None, 'Workspace folder does not exist')
    row('West workspace', 'Initialized .west directory', 'Found' if (workspace / '.west/config').is_file() else '',
        'Ready' if (workspace / '.west/config').is_file() else 'Missing', workspace, reason)
    if source:
        row('Zephyr source', 'Existing checkout; no implicit west update', zephyr_version(source), 'Detected', source, reason)
        # Compare manifest revisions locally. No repository fetching occurs.
        code, text = execute([sys.executable, '-m', 'west', 'list', '--format', '{abspath}'], workspace)
        absent = [p for p in text.splitlines() if not Path(p).is_dir()] if code == 0 else []
        if (Path(sys.prefix) / '.zew-managed-python').is_file():
            row('West modules', 'First-build profile: no external modules', 'Intentionally excluded',
                'Not required for first build', workspace, 'Additional board profiles need separate module setup')
        else:
            row('West modules', 'Manifest project directories',
                str(len(absent)) + ' missing' if code == 0 else 'West listing failed',
                'Present; revisions not audited' if code == 0 and not absent else 'Not ready', workspace,
                '; '.join(absent[:8]) if absent else 'No source/module updates performed; revisions and blobs not audited')
    else:
        row('Zephyr source', 'Selected workspace', '', 'Missing', workspace, reason)
    minimum = required_version(source, 'cmake/modules/python.cmake', r'set\(PYTHON_MINIMUM_REQUIRED\s+([\d.]+)', (3, 12, 0))
    row('Python', '>=' + '.'.join(map(str, minimum)), sys.version.split()[0],
        'Ready' if sys.version_info[:3] >= minimum else 'Incompatible', sys.executable, 'Selected interpreter')
    managed = (Path(sys.prefix) / '.zew-managed-python').is_file()
    isolated = sys.prefix != sys.base_prefix or managed
    incomplete = (Path(sys.prefix) / '.zew-incomplete').exists()
    row('Python environment', 'Isolated environment', 'Managed portable runtime' if managed else 'venv' if isolated else 'System interpreter',
        'Incomplete' if incomplete else 'Ready' if isolated else 'Create isolated environment', sys.prefix,
        'Reuse existing environment' if isolated else 'Create an environment in a new empty directory')
    try:
        version = metadata.version('west')
        west_min = required_version(source, 'cmake/modules/west.cmake', r'set\(WEST_MINIMUM_REQUIRED\s+([\d.]+)', (0, 14, 0))
        row('West', '>=' + '.'.join(map(str, west_min)), version,
            'Ready' if version_tuple(version) and version_tuple(version) >= west_min else 'Incompatible',
            metadata.distribution('west').locate_file(''), 'Uses selected Python via -m west')
    except metadata.PackageNotFoundError:
        row('West', 'See checkout cmake/modules/west.cmake', '', 'Missing', sys.prefix, 'Package installation follows in the installer release')
    emit('PHASE', 'Checking SDK and architecture compilers')
    recommended = (source / 'SDK_VERSION').read_text().strip() if source and (source / 'SDK_VERSION').is_file() else 'See checkout SDK_VERSION'
    hostdirs = scan_sdk(args.sdk, recommended)
    emit('PHASE', 'Checking host tools and Python packages')
    cmake_min = required_version(source, 'CMakeLists.txt', r'cmake_minimum_required\(VERSION\s+([\d.]+)', (3, 20, 5))
    host_tool('cmake', args.cmake, cmake_min)
    host_tool('ninja', args.ninja, (1, 10, 0))
    host_tool('git', args.git, (2, 25, 0))
    host_tool('dtc', args.dtc, extra_dirs=hostdirs)
    if source:
        check_requirements(source)
    emit('DONE', 'Local scan complete; only Ready rows passed their stated checks. A firmware build validates the whole environment.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', required=True)
    for name in ('sdk', 'cmake', 'ninja', 'git', 'dtc'):
        parser.add_argument('--' + name, default='')
    args = parser.parse_args()
    try:
        inventory(args)
    except Exception as error:
        emit('ERROR', str(error))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
