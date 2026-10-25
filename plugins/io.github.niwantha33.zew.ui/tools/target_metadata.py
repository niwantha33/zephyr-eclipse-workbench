# SPDX-License-Identifier: Apache-2.0
"""Exact-target architecture and source requirements from upstream hardware metadata."""
import argparse
from pathlib import Path
import re
import sys


def describe(zephyr, target):
    import yaml
    scripts = str(zephyr / 'scripts')
    if scripts not in sys.path: sys.path.insert(0, scripts)
    import list_boards
    import list_hardware
    base, _, qualifier = target.partition('/')
    name, _, revision = base.partition('@')
    args = argparse.Namespace(board=name, board_dir=[], board_roots=[zephyr], soc_roots=[zephyr])
    boards = list_boards.find_v2_boards(args)
    if name not in boards: raise ValueError('Board not found in selected checkout: ' + name)
    board = boards[name]
    qualifiers = list_boards.board_v2_qualifiers(board) or ['']
    if qualifier not in qualifiers: raise ValueError('Choose an exact supported SoC/core qualifier for ' + name)
    if revision and board.revision_exact and revision not in [r.name for r in board.revisions]:
        raise ValueError('Unsupported exact board revision: ' + revision)
    systems = list_hardware.find_v2_systems(argparse.Namespace(soc_roots=[zephyr]))
    soc = next((s for s in systems.get_socs() if s.name == qualifier.split('/')[0]), None)
    folders = [Path(p) for p in getattr(soc, 'folder', [])]
    vendor = 'unknown'
    for folder in folders:
        try: vendor = folder.resolve().relative_to((zephyr / 'soc').resolve()).parts[0]; break
        except (ValueError, IndexError): pass
    directories = board.directories if isinstance(board.directories, list) else [board.directories]
    exact = name + ('/' + qualifier if qualifier else '')
    exact_rows, legacy_rows = [], []
    for directory in directories:
        for path in Path(directory).glob('*.yaml'):
            if path.stat().st_size > 262144: continue
            data = yaml.safe_load(path.read_text(encoding='utf-8'))
            if not isinstance(data, dict) or not data.get('arch'): continue
            identifiers = data.get('identifier', '')
            identifiers = identifiers.split() if isinstance(identifiers, str) else identifiers
            if exact in identifiers: exact_rows.append(dict(data, metadata_path=path))
            elif name in identifiers and len(board.socs) == 1: legacy_rows.append(dict(data, metadata_path=path))
    rows = exact_rows or legacy_rows
    architectures = {r['arch'] for r in rows}
    if len(architectures) != 1:
        raise ValueError('Architecture is not uniquely described for exact target ' + target + '; no compiler was guessed')
    result = {'zephyr': zephyr, 'board': target, 'soc': qualifier.split('/')[0], 'vendor': vendor,
              'arch': architectures.pop(), 'type': rows[0].get('type', 'unknown'),
              'family': getattr(soc, 'family', '') or '', 'series': getattr(soc, 'series', '') or '',
              'folders': folders, 'board_dirs': [Path(p) for p in directories]}
    defaults = {}
    for row in rows:
        path = row['metadata_path'].with_name(row['metadata_path'].stem + '_defconfig')
        if path.is_file():
            for key,value in re.findall(r'^CONFIG_([A-Za-z0-9_]+)=(.*)$',path.read_text(encoding='utf-8'),re.MULTILINE):
                if key in ('ISA_ARCV3','ARM64','SOC_TOOLCHAIN_NAME') and key in defaults and defaults[key] != value.strip():
                    raise ValueError('Conflicting exact-target compiler defaults for ' + target)
                defaults[key] = value.strip()
    result['defaults'] = defaults
    result['source_text'] = source_text(result)
    return result


def source_text(info):
    paths = set()
    for directory in info['board_dirs']: paths.update(directory.rglob('*'))
    for root in info['folders']:
        candidates = [root / token for token in (info['soc'], info['series'], info['family']) if token and (root / token).is_dir()]
        if candidates:
            paths.update(root.glob('*')); paths.update(candidates[0].rglob('*'))
            if (root / 'common').is_dir(): paths.update((root / 'common').rglob('*'))
        else: paths.update(root.rglob('*'))
    text, total = [], 0
    for path in sorted(paths):
        if path.is_file() and (path.name.startswith('Kconfig') or path.name == 'CMakeLists.txt' or path.suffix in ('.cmake', '.c', '.h', '.dts', '.dtsi')):
            if path.stat().st_size > 524288: continue
            chunk = path.read_text(encoding='utf-8', errors='replace'); total += len(chunk)
            if total > 16777216: raise ValueError('Selected hardware source metadata exceeds scan limit')
            text.append(chunk)
    return '\n'.join(text)


def compiler(info, assets, application=None):
    arch = info['arch']
    defaults = dict(info.get('defaults',{}))
    if application:
        path = Path(application) / 'prj.conf'
        if path.is_file():
            defaults.update((key,value.strip()) for key,value in re.findall(r'^CONFIG_([A-Za-z0-9_]+)=(.*)$',path.read_text(encoding='utf-8'),re.MULTILINE))
    # Follow sdk-ng v1.0.1 cmake/zephyr/gnu/target.cmake: ARCv3 uses
    # arc64 even for its 32-bit CPUs, and legacy ARM64 selects AArch64.
    if arch == 'arc' and defaults.get('ISA_ARCV3') == 'y': arch = 'arc64'
    if arch == 'arm' and defaults.get('ARM64') == 'y': arch = 'arm64'
    aliases = {'arm':'arm-zephyr-eabi', 'arm64':'aarch64-zephyr-elf', 'x86':'x86_64-zephyr-elf',
               'riscv':'riscv64-zephyr-elf', 'microblaze':'microblazeel-zephyr-elf', 'openrisc':'or1k-zephyr-elf'}
    if arch == 'xtensa':
        explicit = defaults.get('SOC_TOOLCHAIN_NAME','').strip(chr(34))
        names = [explicit] if explicit else re.findall(r'config SOC_TOOLCHAIN_NAME\s+string\s+default "([A-Za-z0-9_]+)"', info['source_text'])
        matches = {'xtensa-' + name + '_zephyr-elf' for name in names if 'xtensa-' + name + '_zephyr-elf' in assets}
    else: matches = {aliases.get(arch, arch + '-zephyr-elf')} & set(assets)
    if len(matches) != 1: raise ValueError('No unique reviewed SDK compiler for ' + info['board'] + ' (' + arch + ')')
    return next(iter(matches))


def hal_modules(info, manifest):
    # The closest matching SoC path is more specific than the vendor umbrella.
    candidates = {}
    for folder in info['folders']:
        parts = folder.parts
        for index, token in enumerate(parts):
            name = 'hal_' + token
            if name in manifest: candidates[name] = max(candidates.get(name, -1), index)
    selected = []
    if info['type'] != 'qemu':
        if candidates:
            depth = max(candidates.values()); selected.extend(sorted(n for n,d in candidates.items() if d == depth))
        elif 'hal_' + info['vendor'] in manifest: selected.append('hal_' + info['vendor'])
    if not selected and info['type'] != 'qemu':
        root=info.get('zephyr',Path('/__no_core__'))
        foreign=re.findall(r'#include\s*<(' + re.escape(info['vendor']) + r'/[^>]+)>',info['source_text'])
        if any(not (root/'dts'/header).is_file() and not (root/'include'/header).is_file() for header in foreign):
            raise ValueError('Required HAL missing from pinned manifest: hal_' + info['vendor'])
    references = set(re.findall(r'ZEPHYR_([A-Z0-9_]+)_MODULE_DIR', info['source_text']))
    for name in manifest:
        if name.upper().replace('-', '_') in references and name not in selected: selected.append(name)
    architecture_hal = 'hal_' + info['arch']
    if architecture_hal in manifest and architecture_hal not in selected: selected.append(architecture_hal)
    return selected
