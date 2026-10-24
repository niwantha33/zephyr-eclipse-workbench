# SPDX-License-Identifier: Apache-2.0
"""Use the selected checkout's resolver, including SoC CPU clusters and extensions."""
import argparse
from pathlib import Path
import sys


def catalogue(zephyr, roots=()):
    sys.path.insert(0, str(zephyr / 'scripts'))
    import list_boards
    import list_hardware
    args = argparse.Namespace(board=None, board_dir=[], board_roots=[zephyr, *roots], soc_roots=[zephyr, *roots])
    systems = list_hardware.find_v2_systems(argparse.Namespace(soc_roots=args.soc_roots))
    rows = set()
    socs = {soc.name: soc for soc in systems.get_socs()}
    for board in list_boards.find_v2_boards(args).values():
        revisions = [''] + [r.name for r in board.revisions]
        for qualifier in list_boards.board_v2_qualifiers(board) or ['']:
            soc_name = qualifier.split('/')[0]
            soc = socs.get(soc_name)
            # Keep the chip vendor distinct from the board manufacturer.
            folders = [str(p).replace('\\', '/').split('/') for p in getattr(soc, 'folder', [])] if isinstance(getattr(soc, 'folder', None), list) else []
            vendor = 'unknown'
            for parts in folders:
                if 'soc' in parts and len(parts) > parts.index('soc') + 1:
                    vendor = parts[parts.index('soc') + 1]; break
            for revision in revisions:
                target = board.name + ('@' + revision if revision else '') + ('/' + qualifier if qualifier else '')
                rows.add((vendor, board.vendor or 'unknown', soc_name or '-', board.name, revision or 'default', qualifier or '-', target))
    return sorted(rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--zephyr', type=Path, required=True)
    parser.add_argument('--root', type=Path, action='append', default=[])
    args = parser.parse_args()
    for row in catalogue(args.zephyr.resolve(), args.root):
        if any('\t' in value or '\n' in value for value in row):
            raise ValueError('Invalid hardware metadata')
        print('\t'.join(row))


if __name__ == '__main__':
    main()
