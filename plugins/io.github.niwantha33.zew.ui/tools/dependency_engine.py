# SPDX-License-Identifier: Apache-2.0
"""Code-owned dependency providers. Metadata supplies data, never executable code.

The existing board planner is the bootstrap resolver; generated Kconfig and
Ninja requirements supply the second pass. No provider is selected by log text.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


class DependencyError(ValueError):
    pass


class InstallationLock:
    """OS-held cross-process lock; process termination releases it automatically."""
    def __init__(self, root):
        self.path = Path(root) / '.zew-dependencies.lock'
        self.stream = None

    def __enter__(self):
        if self.path.is_symlink():
            raise DependencyError('Dependency lock must not be a symlink: ' + str(self.path))
        self.stream = self.path.open('a+b')
        self.stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                if self.path.stat().st_size == 0:
                    self.stream.write(b'0'); self.stream.flush(); self.stream.seek(0)
                msvcrt.locking(self.stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            self.stream.close(); self.stream = None
            raise DependencyError('Another process is preparing this managed environment; retry after it finishes.') from error
        return self

    def __exit__(self, *unused):
        if self.stream is not None:
            if os.name == 'nt':
                import msvcrt
                self.stream.seek(0); msvcrt.locking(self.stream.fileno(), msvcrt.LK_UNLCK, 1)
            self.stream.close()


def atomic_json(path, value):
    path = Path(path)
    if path.is_symlink() or path.parent.is_symlink():
        raise DependencyError('Refusing symlinked dependency state: ' + str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.partial')
    if temporary.is_symlink():
        raise DependencyError('Refusing symlinked temporary dependency state')
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


class Context:
    def __init__(self, backend, details, root, sdk, workspace, python, git):
        self.backend, self.details = backend, details
        self.root, self.sdk, self.workspace = map(Path, (root, sdk, workspace))
        self.python, self.git = python, git
        self.evidence = []

    def record(self, kind, name, **proof):
        self.evidence.append(dict(kind=kind, name=name, mandatory=True, **proof))


class CompilerProvider:
    name = 'compiler'

    def prepare(self, ctx):
        data = ctx.details
        ctx.backend.prepare_compiler(ctx.root, ctx.sdk, data['compiler'], data['asset'])
        executable = ctx.sdk / 'gnu' / data['compiler'] / 'bin' / (data['compiler'] + '-gcc.exe')
        machine = subprocess.check_output([str(executable), '-dumpmachine'], text=True,
                                          timeout=30, stdin=subprocess.DEVNULL).strip()
        if machine != data['compiler']:
            raise DependencyError('Compiler architecture mismatch: expected ' + data['compiler'] + ', received ' + machine)
        ctx.record('compiler', data['compiler'], sdk=ctx.backend.PINNED_SDK,
                   archive_sha256=data['asset']['sha256'], machine=machine)


class WestModuleProvider:
    name = 'west-modules'

    def prepare(self, ctx):
        ctx.backend.prepare_modules(ctx.details, ctx.workspace, ctx.python, ctx.git)
        ctx.backend.activate_modules(ctx.details, ctx.workspace, ctx.python)
        for name in ctx.details['modules']:
            revision, relative = ctx.details['manifest'][name]
            destination = ctx.workspace / relative
            dirty = subprocess.check_output([str(ctx.git), '-C', str(destination), 'status',
                                             '--porcelain', '--untracked-files=normal'],
                                            text=True, timeout=30).strip()
            if dirty:
                raise DependencyError('Pinned module has local changes; preserved: ' + str(destination)
                                      + '. Restore it explicitly or select a separate managed setup.')
            ctx.record('west-module', name, revision=revision, path=str(relative))


class PythonPackageProvider:
    """Only code-owned reviewed locks can install Python utilities."""
    name = 'python-packages'

    def prepare(self, ctx):
        # The selected HAL, rather than board-maker identity, supplies this
        # requirement. One existing lock supports Espressif image utilities.
        if 'hal_espressif' in ctx.details['modules']:
            ctx.backend.legacy.install_python_tools(ctx.root)
            lock = ctx.backend.HERE / 'esp32s3-python-lock.json'
            ctx.record('python-utility', 'esptool', lock_sha256=hashlib.sha256(lock.read_bytes()).hexdigest())


class HostToolProvider:
    def __init__(self, name, install):
        self.name, self.install = name, install

    def prepare(self, ctx):
        self.install(ctx)
        ctx.record('host-tool', self.name)


def signing_provider(ctx):
    ctx.backend.install_signing_tools(ctx.root, ctx.workspace, ctx.python, ctx.details['manifest'])
    lock = ctx.backend.HERE / 'mcuboot-python-lock.json'
    ctx.record('python-utility', 'imgtool', lock_sha256=hashlib.sha256(lock.read_bytes()).hexdigest())


class RecoveryManager:
    """One attempt per recognised capability; source errors are never retries."""
    def __init__(self, providers, limit=2):
        self.providers, self.limit, self.attempted = providers, limit, set()

    def resolve(self, required):
        unknown = set(required) - self.providers.keys()
        if unknown:
            raise DependencyError('No reviewed pinned provider for required host tools: ' + ', '.join(sorted(unknown)))
        if set(required) & self.attempted or len(self.attempted | set(required)) > self.limit:
            raise DependencyError('Dependency recovery budget exhausted; inspect the original configure error.')
        self.attempted.update(required)
        return [self.providers[name] for name in sorted(required)]


class DependencyResolver:
    def __init__(self, bootstrap=None, host_tools=None):
        self.bootstrap = bootstrap if bootstrap is not None else [CompilerProvider(), WestModuleProvider(), PythonPackageProvider()]
        self.host_tools = host_tools if host_tools is not None else {'IMGTOOL': HostToolProvider('IMGTOOL', signing_provider)}

    def prepare(self, ctx, required=()):
        required = set(required)
        if ctx.details.get('signing'):
            required.add('IMGTOOL')
        # Reject unsupported requests before any mutation or download.
        host = RecoveryManager(self.host_tools).resolve(required)
        with InstallationLock(ctx.root):
            for provider in [*self.bootstrap, *host]:
                ctx.backend.setup.log('Dependency stage: ' + provider.name)
                try:
                    provider.prepare(ctx)
                except (OSError, ValueError, subprocess.SubprocessError) as error:
                    raise DependencyError('Dependency stage ' + provider.name + ' failed: ' + str(error)) from error
            report = dict(schema=1, board=ctx.details['board'], soc=ctx.details['soc'],
                          vendor=ctx.details['vendor'], dependencies=ctx.evidence)
            report['fingerprint'] = hashlib.sha256(json.dumps(report, sort_keys=True).encode()).hexdigest()
            key = hashlib.sha256(ctx.details['board'].encode()).hexdigest()[:16]
            atomic_json(ctx.root / '.zew-dependency-state' / (key + '.json'), report)
            ctx.backend.setup.log('Dependencies verified: ' + report['fingerprint'])
            return report
