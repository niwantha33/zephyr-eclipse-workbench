# SPDX-License-Identifier: Apache-2.0
"""Resolve the pinned West OpenOCD flash image and check its thread metadata offline."""
import argparse
import base64
from pathlib import Path


def verify_hex(data, expected):
    remaining = dict(expected)
    observed = {}
    base = 0
    ended = False
    for number, line in enumerate(data.decode('ascii').splitlines(), 1):
        if not line.strip():
            continue
        if ended or not line.startswith(':') or len(line) > 521:
            raise ValueError(f'Invalid Intel HEX record at line {number}')
        try:
            record = bytes.fromhex(line[1:])
        except ValueError as error:
            raise ValueError(f'Invalid Intel HEX data at line {number}') from error
        if len(record) < 5 or len(record) != record[0] + 5 or sum(record) & 255:
            raise ValueError(f'Invalid Intel HEX checksum/length at line {number}')
        count, address, kind = record[0], int.from_bytes(record[1:3], 'big'), record[3]
        payload = record[4:-1]
        if kind == 0:
            for index, value in enumerate(payload):
                location = base + address + index
                if location in expected:
                    if location in observed and observed[location] != value:
                        raise ValueError(f'Conflicting Intel HEX bytes at 0x{location:x}')
                    observed[location] = value
                    if value != expected[location]:
                        raise ValueError(f'Flash image differs from ELF at 0x{location:x}: expected {expected[location]}, image {value}')
                    remaining.pop(location, None)
        elif kind == 1 and count == 0 and address == 0:
            ended = True
        elif kind in (2, 4) and count == 2 and address == 0:
            base = int.from_bytes(payload, 'big') << (4 if kind == 2 else 16)
        elif kind in (3, 5) and count == 4 and address == 0:
            pass  # execution-start record; no programmed bytes
        else:
            raise ValueError(f'Unsupported/invalid Intel HEX record at line {number}')
    if not ended:
        raise ValueError('Intel HEX image has no end record')
    if remaining:
        raise ValueError(f'Flash image omits ELF thread metadata at 0x{next(iter(remaining)):x}; rebuild this exact configuration')


def resolve(build, elf, regions):
    import yaml
    build = build.resolve(strict=True)
    metadata = build / 'zephyr/runners.yaml'
    if metadata.stat().st_size > 1024 * 1024:
        raise ValueError('Oversized runner metadata')
    document = yaml.safe_load(metadata.read_text(encoding='utf-8'))
    if document.get('flash-runner') != 'openocd':
        raise ValueError('Flash image inspection requires the declared OpenOCD runner')
    config = document['config']
    arguments = document.get('args', {}).get('openocd', [])
    if not isinstance(arguments, list) or not all(isinstance(arg, str) for arg in arguments):
        raise ValueError('Invalid OpenOCD runner arguments')
    parser = argparse.ArgumentParser(add_help=False)
    for value in ('hex', 'elf', 'bin'):
        parser.add_argument('--use-' + value, action='store_const', const=value, dest='legacy')
        parser.add_argument('--' + value + '-file')
    parser.add_argument('--file-type', '-t', choices=('hex', 'elf', 'bin'), type=str.lower)
    parser.add_argument('--file', '-f')
    parser.add_argument('--flash-address')
    options, _ = parser.parse_known_args(arguments)
    explicit_kind = options.file_type
    if explicit_kind is None and options.file:
        suffix = Path(options.file).suffix.lower().lstrip('.')
        if suffix in ('hex', 'bin', 'elf'):
            explicit_kind = suffix
    kind = explicit_kind or options.legacy or 'hex'
    image = options.file or config.get('file')
    if image and not Path(image).is_absolute():
        raise ValueError('Explicit flash file must use an absolute path')
    if not image:
        image = getattr(options, kind + '_file') or config.get(kind + '_file')
    if kind not in ('hex', 'elf', 'bin') or not isinstance(image, str) or not image:
        raise ValueError('No supported declared OpenOCD flash image')
    path = Path(image)
    if not path.is_absolute():
        path = metadata.parent / path
    path = path.resolve(strict=True)
    if not path.is_relative_to(build) or path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError('Flash image escapes the selected build or exceeds the size limit')
    data = path.read_bytes()
    if kind == 'hex':
        verify_hex(data, regions)
    elif kind == 'elf':
        if path != elf.resolve(strict=True):
            raise ValueError('Declared flash ELF differs from the selected debug ELF')
    else:
        if not options.flash_address:
            raise ValueError('BIN flash image needs the board-declared --flash-address')
        base = int(options.flash_address, 0)
        for address, expected in regions.items():
            offset = address - base
            if not 0 <= offset < len(data) or data[offset] != expected:
                raise ValueError(f'BIN image omits/differs from ELF thread metadata at 0x{address:x}')
    return path, kind


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', required=True, type=Path)
    parser.add_argument('--elf', required=True, type=Path)
    parser.add_argument('--region', action='append', required=True)
    args = parser.parse_args()
    expected = {}
    for region in args.region:
        address, value = region.split(':', 1)
        address = int(address, 16)
        payload = bytes.fromhex(value)
        if not payload or len(payload) > 256 or address <= 0:
            raise ValueError('Invalid ELF metadata region')
        for index, value in enumerate(payload):
            expected[address + index] = value
    path, kind = resolve(args.build, args.elf, expected)
    print('IMAGE\t' + base64.b64encode(str(path).encode('utf-8')).decode('ascii'))
    print('TYPE\t' + kind)


if __name__ == '__main__':
    main()
