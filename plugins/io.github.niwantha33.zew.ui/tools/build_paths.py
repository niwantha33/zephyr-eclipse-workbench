# SPDX-License-Identifier: Apache-2.0
"""Private native Windows aliases for Zephyr's space-sensitive CMake/Kconfig.

No shell, source copying/moving, administrator privilege, or volume setting
changes. Junctions are owned by the managed setup and verified before reuse.
"""
import argparse
import base64
import ctypes
import hashlib
import os
from pathlib import Path
import re
import struct
import tempfile


def requires_alias(value):
    return bool(re.search(r'[^!-~]|[;]', str(value)))


def create_junction(entry, target):
    """Attach an empty, newly owned directory using the Windows mount-point API."""
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    open_file = kernel.CreateFileW
    open_file.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p,
                          ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
    open_file.restype = ctypes.c_void_p
    control = kernel.DeviceIoControl
    control.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32,
                        ctypes.c_void_p, ctypes.c_uint32, ctypes.POINTER(ctypes.c_uint32), ctypes.c_void_p]
    control.restype = ctypes.c_int
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = open_file(str(entry), 0x40000000, 0, None, 3, 0x02200000, None)
    if handle == ctypes.c_void_p(-1).value:
        raise OSError(ctypes.get_last_error(), 'Cannot open private build path alias: ' + str(entry))
    # MOUNT_POINT_REPARSE_BUFFER: data starts with two UTF-16 name descriptors.
    original = str(target)
    substitute = ('\\??\\UNC\\' + original[2:] if original.startswith('\\\\') else '\\??\\' + original).encode('utf-16-le')
    display = original.encode('utf-16-le')
    names = substitute + b'\0\0' + display + b'\0\0'
    data = struct.pack('<IHHHHHH', 0xA0000003, 8 + len(names), 0,
                       0, len(substitute), len(substitute) + 2, len(display)) + names
    returned = ctypes.c_uint32()
    buffer = ctypes.create_string_buffer(data)
    try:
        if not control(handle, 0x000900A4, buffer, len(data), None, 0, ctypes.byref(returned), None):
            raise OSError(ctypes.get_last_error(), 'Cannot create private Windows build path alias')
    finally:
        kernel.CloseHandle(handle)


def tool_path(path, managed_root=None, windows=None):
    path = Path(path).resolve(strict=True)
    windows = os.name == 'nt' if windows is None else windows
    if not windows or not requires_alias(path):
        return path.as_posix()
    if managed_root is None or requires_alias(Path(managed_root).resolve(strict=True)):
        raise ValueError('This managed setup needs an ASCII path without spaces for private build aliases; application files were preserved.')
    parent = Path(managed_root).resolve() / '.zew-path-aliases'
    if parent.is_symlink() or (hasattr(parent, 'is_junction') and parent.is_junction()):
        raise ValueError('Private path alias root is itself a link; preserved')
    marker = parent / '.zew-owned'
    if not parent.exists():
        parent.mkdir()
        marker.write_text('zew-build-paths-v1\n', encoding='utf-8')
    if not marker.is_file() or marker.read_text(encoding='utf-8') != 'zew-build-paths-v1\n':
        raise ValueError('Unowned path alias directory preserved: ' + str(parent))
    name = hashlib.sha256(str(path).casefold().encode('utf-8')).hexdigest()[:24]
    entry = parent / name
    if entry.exists() or entry.is_symlink() or (hasattr(entry, 'is_junction') and entry.is_junction()):
        if not entry.is_junction() or entry.resolve(strict=True) != path:
            raise ValueError('Conflicting private path alias preserved: ' + str(entry))
        return entry.as_posix()
    stage = Path(tempfile.mkdtemp(prefix='.stage-', dir=parent))
    try:
        create_junction(stage, path)
        if stage.resolve(strict=True) != path:
            raise ValueError('Private junction does not resolve to the selected application')
        stage.rename(entry)
    finally:
        # rmdir removes only this owned junction/empty entry, never its target.
        if stage.exists() or (hasattr(stage, 'is_junction') and stage.is_junction()):
            os.rmdir(stage)
    return entry.as_posix()


def resolve(application, build, workspace, sdk, managed_root=None, windows=None):
    application = Path(application).resolve(strict=True)
    build = Path(build)
    owned = application / '.zew-build'
    resolved = build.resolve()
    if resolved == owned or not resolved.is_relative_to(owned):
        raise ValueError('Build output must be an independent directory inside this application .zew-build')
    build.mkdir(parents=True, exist_ok=True)
    source = tool_path(application, managed_root, windows)
    output = (Path(source) / build.resolve().relative_to(application)).as_posix()
    if (os.name == 'nt' if windows is None else windows) and requires_alias(output):
        raise ValueError('Managed build directory name must be ASCII without spaces')
    fresh = False
    cache = build / 'CMakeCache.txt'
    if cache.is_file():
        if cache.stat().st_size > 8 * 1024 * 1024:
            raise ValueError('CMake cache exceeds managed inspection limit')
        for line in cache.read_text(encoding='utf-8', errors='replace').splitlines():
            if line.startswith('CMAKE_HOME_DIRECTORY:INTERNAL='):
                old = line.partition('=')[2]
                fresh = source != application.as_posix() and requires_alias(old)
    return [source, output, tool_path(workspace, managed_root, windows),
            tool_path(sdk, managed_root, windows), str(fresh).lower()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('application', 'build', 'workspace', 'sdk', 'root'):
        parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args()
    values = resolve(args.application, args.build, args.workspace, args.sdk, args.root)
    print('ZEW_PATHS_1\t' + '\t'.join(base64.b64encode(value.encode('utf-8')).decode('ascii') for value in values))


if __name__ == '__main__': main()
