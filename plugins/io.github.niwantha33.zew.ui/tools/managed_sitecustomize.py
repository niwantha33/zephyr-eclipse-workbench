# SPDX-License-Identifier: Apache-2.0
"""Restore sibling imports only for scripts in the managed Zephyr scripts tree.

Installed as python/sitecustomize.py. The embedded runtime keeps its ._pth
isolation: environment, registry, user packages and arbitrary cwd stay excluded.
"""
from pathlib import Path
import sys


def configure():
    if not sys.argv or sys.argv[0] in ('', '-c', '-m', '-'):
        return
    scripts = Path(__file__).resolve().parent.parent / 'workspace/zephyr/scripts'
    script = Path(sys.argv[0]).resolve()
    if script.suffix == '.py' and script.is_file() and script.is_relative_to(scripts.resolve()):
        directory = str(script.parent)
        if directory not in sys.path:
            sys.path.insert(0, directory)


configure()
