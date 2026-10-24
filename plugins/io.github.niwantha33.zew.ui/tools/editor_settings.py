# SPDX-License-Identifier: Apache-2.0
"""Derive editor settings from CMake and the selected SDK GCC, without a shell."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

LIMIT = 8 * 1024 * 1024


def windows_split(command):
    """MS C runtime quoting: only backslashes immediately before quotes escape."""
    result = []; i = 0
    while i < len(command):
        while i < len(command) and command[i].isspace(): i += 1
        if i == len(command): break
        token = []; quoted = False
        while i < len(command):
            if command[i].isspace() and not quoted: break
            if command[i] == '\\':
                start = i
                while i < len(command) and command[i] == '\\': i += 1
                count = i - start
                if i < len(command) and command[i] == '"':
                    token.extend('\\' * (count // 2))
                    if count % 2: token.append('"')
                    else: quoted = not quoted
                    i += 1
                else: token.extend('\\' * count)
            elif command[i] == '"': quoted = not quoted; i += 1
            else: token.append(command[i]); i += 1
        if quoted: raise ValueError('Unterminated quote in compiler command')
        result.append(''.join(token))
    return result


def arguments(entry, windows=None):
    if isinstance(entry.get('arguments'), list):
        if not all(isinstance(a, str) for a in entry['arguments']): raise ValueError('Invalid compiler arguments')
        return entry['arguments']
    return windows_split(entry['command']) if (os.name == 'nt' if windows is None else windows) else shlex.split(entry['command'])


def absolute(value, directory):
    path = Path(value)
    return (path if path.is_absolute() else directory / path).resolve()


def expand_response(args, directory, depth=0):
    if depth > 4: raise ValueError('Compiler response files are nested too deeply')
    result = []
    for arg in args:
        if arg.startswith('@'):
            path = absolute(arg[1:], directory)
            if path.stat().st_size > LIMIT: raise ValueError('Compiler response file is too large')
            content = path.read_text(encoding='utf-8-sig')
            nested = windows_split(content) if os.name == 'nt' else shlex.split(content)
            result.extend(expand_response(nested, directory, depth + 1))
        else: result.append(arg)
    if len(result) > 20000: raise ValueError('Too many compiler arguments')
    return result


def preprocess_flags(args, directory):
    flags = []; forced = []; macros = []; includes = []; i = 0
    pair = ('-I', '-isystem', '-iquote', '-idirafter', '-include', '-imacros', '-D', '-U', '-isysroot')
    while i < len(args):
        arg = args[i]; i += 1
        option = next((p for p in pair if arg == p or (p in ('-I', '-D', '-U') and arg.startswith(p))), None)
        if option:
            value = arg[len(option):]
            if not value:
                if i == len(args): raise ValueError('Missing preprocessor option value: ' + option)
                value = args[i]; i += 1
            if option not in ('-D', '-U'): value = str(absolute(value, directory))
            flags.extend([option, value])
            if option in ('-I', '-isystem', '-iquote', '-idirafter'): includes.append(value)
            if option == '-include': forced.append(value)
            if option == '-imacros': macros.append(value)
        elif arg.startswith('--sysroot='):
            flags.append('--sysroot=' + str(absolute(arg.split('=', 1)[1], directory)))
        elif (arg.startswith(('-std=', '-m')) or re.fullmatch(r'-O([0-3sgz]|fast)?', arg)
                or arg in ('-nostdinc', '-nostdinc++', '-pthread', '-ansi',
                '-ffreestanding', '-fhosted', '-fno-builtin', '-fexceptions', '-fno-exceptions', '-frtti', '-fno-rtti',
                '-ffast-math', '-fno-fast-math', '-fpic', '-fPIC', '-fpie', '-fPIE',
                '-fno-pic', '-fno-PIC', '-fno-pie', '-fno-PIE', '-fshort-enums', '-fshort-wchar',
                '-funsigned-char', '-fsigned-char', '-fstack-protector', '-fstack-protector-all',
                '-fstack-protector-strong', '-fno-stack-protector')):
            flags.append(arg)
    return flags, includes, forced, macros


def probe(compiler, flags, language, directory, sdk):
    compiler = absolute(compiler, directory)
    if not compiler.is_relative_to(sdk.resolve()) or not re.search(r'(gcc|g\+\+)(-\d+(\.\d+)*)?(\.exe)?$', compiler.name):
        raise ValueError('Editor probe requires GCC from the selected SDK: ' + str(compiler))
    environment = dict(os.environ, LC_ALL='C', LANG='C')
    result = subprocess.run([str(compiler), *flags, '-E', '-dM', '-v', '-x', language, '-'], input='',
            cwd=directory, env=environment, text=True, encoding='utf-8', errors='replace',
            capture_output=True, timeout=30, check=True, shell=False)
    if len(result.stdout) + len(result.stderr) > LIMIT: raise ValueError('Compiler probe output is too large')
    defines = []
    for line in result.stdout.splitlines():
        if line.startswith('#define '):
            name, _, value = line[8:].partition(' '); defines.append((name, value))
    paths = []; active = False
    for line in result.stderr.splitlines():
        if '#include <...> search starts here:' in line or '#include "..." search starts here:' in line: active = True
        elif 'End of search list.' in line: active = False
        elif active:
            value = line.strip().removesuffix(' (framework directory)')
            if value: paths.append(str(absolute(value, directory)))
    if not defines: raise ValueError('SDK compiler returned no preprocessor definitions')
    return paths, defines


def collect(project, build, sdk):
    database = build / 'compile_commands.json'
    if database.stat().st_size > 32 * LIMIT: raise ValueError('Compilation database exceeds limit')
    entries = json.loads(database.read_text(encoding='utf-8-sig'))
    if not isinstance(entries, list): raise ValueError('Invalid compilation database')
    rows = []; seen = set(); cache = {}
    for entry in entries:
        directory = absolute(entry['directory'], build)
        source = absolute(entry['file'], directory)
        if not source.is_relative_to(project) or source.is_relative_to(build): continue
        if source.suffix not in ('.c', '.cc', '.cpp', '.cxx', '.C'): continue
        relative = source.relative_to(project).as_posix()
        if relative in seen: raise ValueError('Ambiguous compiler settings for application source: ' + relative)
        seen.add(relative)
        args = expand_response(arguments(entry), directory)
        if not args: raise ValueError('Empty compiler command')
        flags, includes, forced, macro_files = preprocess_flags(args[1:], directory)
        language = 'c' if source.suffix == '.c' else 'c++'
        if '-x' in args:
            language = args[args.index('-x') + 1]
            if language not in ('c', 'c++'): raise ValueError('Unsupported compiler language')
        key = (args[0], *flags, language, str(directory))
        if key not in cache: cache[key] = probe(args[0], flags, language, directory, sdk)
        system, defines = cache[key]
        rows.append(('F', relative, language, ''))
        rows.extend(('I', relative, value, '') for value in dict.fromkeys([*includes, *system]))
        rows.extend(('H', relative, value, '') for value in dict.fromkeys(forced))
        rows.extend(('A', relative, value, '') for value in dict.fromkeys(macro_files))
        rows.extend(('D', relative, name, value) for name, value in defines)
    if not seen: raise ValueError('No application C/C++ compiler commands found; rebuild the application first')
    if len(seen) > 512 or len(rows) > 100000: raise ValueError('Editor settings exceed supported size')
    return rows


def write(rows, output):
    def encode(value): return base64.b64encode(value.encode('utf-8')).decode('ascii')
    lines = ['ZEW_EDITOR_1'] + ['\t'.join([kind, *map(encode, (file, name, value))]) for kind, file, name, value in rows]
    lines.append('DONE\t' + str(sum(row[0] == 'F' for row in rows)) + '\t' + str(len(rows)))
    data = ('\n'.join(lines) + '\n').encode('utf-8')
    if len(data) > LIMIT: raise ValueError('Editor settings exceed file size limit')
    temporary = output.with_suffix('.partial'); temporary.write_bytes(data); temporary.replace(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('project', 'build', 'sdk', 'output'): parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args()
    try:
        rows = collect(args.project.resolve(), args.build.resolve(), args.sdk.resolve())
        write(rows, args.output)
        print('Editor settings verified: ' + str(sum(r[0] == 'F' for r in rows)) + ' application source file(s)', flush=True)
    except Exception as error:
        print('Editor settings failed: ' + str(error), flush=True); raise SystemExit(1)
