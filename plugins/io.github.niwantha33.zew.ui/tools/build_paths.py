# SPDX-License-Identifier: Apache-2.0
"""Stable native Windows aliases for tools that tokenize space-containing paths.

Uses existing filesystem short names only. Never copies or moves application
files, creates links, edits Zephyr, or changes volume/global Windows settings.
"""
import argparse
import base64
import ctypes
import os
from pathlib import Path
import re


def requires_alias(value):
    return bool(re.search(r'[^!-~]|[;]', str(value)))


def short_name(path):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    function = kernel.GetShortPathNameW
    function.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint32]
    function.restype = ctypes.c_uint32
    size = function(str(path), None, 0)
    if not size:
        raise OSError(ctypes.get_last_error(), 'Windows cannot resolve a stable short path: ' + str(path))
    buffer = ctypes.create_unicode_buffer(size)
    count = function(str(path), buffer, size)
    if not count or count >= size:
        raise OSError('Windows short path changed during resolution: ' + str(path))
    return buffer.value


def tool_path(path, windows=None):
    path = Path(path).resolve(strict=True)
    windows = os.name == 'nt' if windows is None else windows
    if not windows or not requires_alias(path):
        return path.as_posix()
    alias = short_name(path)
    if requires_alias(alias) or Path(alias).resolve(strict=True) != path:
        raise ValueError('This volume has no verified space-free ASCII alias for ' + str(path)
                         + '. Use an ASCII path without spaces for this managed configuration; source files were preserved.')
    return alias.replace('\\', '/')


def resolve(application, build, workspace, sdk, windows=None):
    application = Path(application).resolve(strict=True)
    build = Path(build)
    owned = application / '.zew-build'
    resolved = build.resolve()
    if resolved == owned or not resolved.is_relative_to(owned):
        raise ValueError('Build output must be an independent directory inside this application .zew-build')
    build.mkdir(parents=True, exist_ok=True)
    source = tool_path(application, windows)
    output = tool_path(build, windows)
    fresh = False
    cache = build / 'CMakeCache.txt'
    if cache.is_file():
        if cache.stat().st_size > 8 * 1024 * 1024:
            raise ValueError('CMake cache exceeds managed inspection limit')
        for line in cache.read_text(encoding='utf-8', errors='replace').splitlines():
            if line.startswith('CMAKE_HOME_DIRECTORY:INTERNAL='):
                old = line.partition('=')[2]
                fresh = source != application.as_posix() and requires_alias(old)
    return [source, output, tool_path(workspace, windows), tool_path(sdk, windows), str(fresh).lower()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('application', 'build', 'workspace', 'sdk'):
        parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args()
    values = resolve(args.application, args.build, args.workspace, args.sdk)
    print('ZEW_PATHS_1\t' + '\t'.join(base64.b64encode(value.encode('utf-8')).decode('ascii') for value in values))


if __name__ == '__main__': main()
