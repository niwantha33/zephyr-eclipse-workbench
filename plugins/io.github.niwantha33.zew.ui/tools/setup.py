#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Pinned, isolated Windows first-build profile; invoked by the Eclipse wizard.

No global PATH/registry edits, no module/blob updates, no user-source replacement.
The Java bootstrap reserves the directory and holds its installation lock.
"""
import argparse
from email.parser import BytesParser
import hashlib
import importlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tempfile
import tarfile
import time
import urllib.parse
import urllib.request
import zipfile

SDK_ASSETS = [
    {'file': 'zephyr-sdk-1.0.1_windows-x86_64_minimal.7z', 'size': 104319274,
     'sha256': '17a557e7c6cea5e5c589508fe45a3f26aca4fa2c6153fe3e7b03aeb4b93fea4b'},
    {'file': 'toolchain_gnu_windows-x86_64_x86_64-zephyr-elf.7z', 'size': 59281601,
     'sha256': 'fc1267f928f1bae6a7fdc5733b89ac09dc20fdb05fadcd165913907c94866873'},
]
for artifact in SDK_ASSETS:
    artifact['url'] = 'https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/' + artifact['file']


def log(message):
    print(message, flush=True)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def valid_cache(path, artifact):
    return path.is_file() and path.stat().st_size == artifact['size'] and digest(path) == artifact['sha256']


def fetch(artifact, cache):
    """Reuse only complete verified artifacts. Retry failed downloads three times."""
    url = urllib.parse.urlparse(artifact['url'])
    if url.scheme != 'https' or url.hostname not in ('files.pythonhosted.org', 'github.com') or url.username:
        raise ValueError('Unapproved package download origin')
    if Path(artifact['file']).name != artifact['file'] or not 0 < artifact['size'] <= 536870912:
        raise ValueError('Invalid package filename or size')
    target = cache / artifact['file']
    if valid_cache(target, artifact):
        log('Using verified cache: ' + target.name)
        return target
    temporary = target.with_name(target.name + '.partial')
    for attempt in range(1, 4):
        try:
            log(f'Downloading {target.name} (attempt {attempt}/3)')
            request = urllib.request.Request(artifact['url'], headers={'User-Agent': 'Zephyr-Eclipse-Workbench-Setup'})
            count = 0
            with urllib.request.urlopen(request, timeout=30) as response, temporary.open('wb') as output:
                if not response.url.startswith('https://'):
                    raise ValueError('Insecure package redirect')
                report = 0
                while block := response.read(262144):
                    count += len(block)
                    if count > artifact['size']:
                        raise ValueError('Download exceeds declared size')
                    output.write(block)
                    if count >= report:
                        log(f'{target.name}: {count} / {artifact["size"]} bytes')
                        report = count + 4194304
            if not valid_cache(temporary, artifact):
                raise ValueError('Size or SHA-256 mismatch: ' + target.name)
            temporary.replace(target)
            return target
        except (OSError, ValueError) as error:
            temporary.unlink(missing_ok=True)
            if attempt == 3:
                raise
            log('Download failed; retrying: ' + str(error))
            time.sleep(attempt)


def validate_lock(lock):
    if lock.get('schema') != 1 or lock.get('profile') != 'first-build-win-x64':
        raise ValueError('Unsupported setup profile')
    if lock.get('zephyr_commit') != '684c9e8f32e4373a21098559f748f06915f950c9' or lock.get('board') != 'qemu_x86/atom':
        raise ValueError('Unreviewed Zephyr source or board profile')
    names = set()
    for package in lock['packages']:
        if package['review'] != 'approved-build-use' or not package.get('license_files'):
            raise ValueError('Package licence review is pending: ' + package['name'])
        if package['name'] in names:
            raise ValueError('Duplicate package in setup lock')
        names.add(package['name'])
        if len(package['sha256']) != 64 or any(c not in '0123456789abcdef' for c in package['sha256']):
            raise ValueError('Invalid artifact digest')


def run(command, directory, environment, timeout=1800):
    # Argument arrays preserve spaces and prevent shell expansion. Parent Java
    # ProcessRunner owns cancellation of this process and its descendants.
    log('Running: ' + Path(str(command[0])).name + ' ' + ' '.join(map(str, command[1:3])))
    subprocess.run(list(map(str, command)), cwd=directory, env=environment,
                   stdin=subprocess.DEVNULL, check=True, timeout=timeout, shell=False)


def installed_executable(root, lock, name):
    package = next(p for p in lock['packages'] if p['name'] == name)
    relative = package['installed_executable']
    path = PurePosixPath(relative)
    if path.is_absolute() or '..' in path.parts or ':' in relative or '\\' in relative:
        raise ValueError('Invalid installed executable path')
    return root / 'python' / relative


def requirements_file(packages, cache, path):
    path.write_text('\n'.join((cache / p['file']).resolve().as_uri() + ' --hash=sha256:' + p['sha256']
                              for p in packages) + '\n', encoding='utf-8')


def source_build_command(python, source, output):
    # Embedded Python's ._pth deliberately excludes cwd. Grant only this
    # verified source directory to this one isolated build process.
    wrapper = ('import sys,runpy;source=sys.argv.pop(1);sys.path.insert(0,source);'
               'runpy.run_path(source+"/setup.py",run_name="__main__")')
    return [python, '-I', '-X', 'utf8', '-c', wrapper, str(source), 'bdist_wheel', '--dist-dir', str(output)]


def build_source_wheel(package, cache, environment):
    """Build the single reviewed source-only dependency without changing ._pth."""
    if package['name'] != 'docopt' or package['version'] != '0.6.2' or package['review'] != 'approved-build-use':
        raise ValueError('Unreviewed source build requested')
    archive = cache / package['file']
    if not valid_cache(archive, package):
        raise ValueError('Source archive changed after download verification')
    log('Building verified Docopt source wheel with an explicit isolated import path')
    with tempfile.TemporaryDirectory(prefix='docopt-build-', dir=cache) as directory:
        stage = Path(directory)
        with tarfile.open(archive) as source_archive:
            members = source_archive.getmembers()
            if len(members) > 500 or sum(m.size for m in members) > 16777216:
                raise ValueError('Source archive exceeds extraction limits')
            for member in members:
                path = PurePosixPath(member.name)
                if (path.is_absolute() or '..' in path.parts or ':' in member.name or '\\' in member.name
                        or not (member.isfile() or member.isdir())):
                    raise ValueError('Unsafe source archive member')
            source_archive.extractall(stage, filter='data')
        source = stage / 'docopt-0.6.2'
        if not (source / 'setup.py').is_file() or not (source / 'docopt.py').is_file():
            raise ValueError('Unexpected reviewed source layout')
        output = stage / 'wheels'; output.mkdir()
        run(source_build_command(sys.executable, source, output), source, environment, 300)
        wheels = list(output.glob('*.whl'))
        if len(wheels) != 1:
            raise ValueError('Source build did not produce exactly one wheel')
        wheel = wheels[0]
        with zipfile.ZipFile(wheel) as built:
            if built.testzip() is not None or 'docopt.py' not in built.namelist():
                raise ValueError('Built source wheel is incomplete or corrupt')
            metadata_files = [p for p in built.namelist() if p.endswith('.dist-info/METADATA')]
            if len(metadata_files) != 1:
                raise ValueError('Built wheel has ambiguous package metadata')
            metadata = BytesParser().parsebytes(built.read(metadata_files[0]))
            if metadata['Name'] != 'docopt' or metadata['Version'] != '0.6.2':
                raise ValueError('Built wheel identity differs from reviewed source')
            if not any('license' in p.lower() for p in built.namelist()):
                raise ValueError('Built wheel did not retain the source licence')
        wheel_hash = digest(wheel)
        target = cache / wheel.name
        partial = target.with_name(target.name + '.partial')
        shutil.copyfile(wheel, partial); partial.replace(target)
        provenance = {'name': package['name'], 'version': package['version'],
                      'source_sha256': package['sha256'], 'wheel_sha256': wheel_hash,
                      'wheel': wheel.name, 'python': sys.version}
        proof = cache / 'docopt-local-build.json'
        proof.write_text(json.dumps(provenance, indent=2) + '\n', encoding='utf-8')
        result = dict(package, file=wheel.name, sha256=wheel_hash, size=target.stat().st_size)
    return result


def install_packages(lock, root, environment):
    cache = root / 'cache'; cache.mkdir(exist_ok=True)
    for package in lock['packages']:
        fetch(package, cache)
    pip = next(p for p in lock['packages'] if p['name'] == 'pip')
    bootstrap = [p for p in lock['packages'] if p['name'] in ('pip', 'setuptools', 'wheel')]
    requirements_file(bootstrap, cache, root / 'bootstrap-requirements.txt')
    command = [sys.executable, '-c', 'import sys,runpy;sys.path.insert(0,sys.argv.pop(1));runpy.run_module("pip",run_name="__main__")',
               str(cache / pip['file']), 'install', '--no-index', '--no-deps', '--require-hashes',
               '--disable-pip-version-check', '--no-warn-script-location', '-r', root / 'bootstrap-requirements.txt']
    run(command, root, environment)
    rest = [p for p in lock['packages'] if p not in bootstrap]
    rest = [p if p['file'].endswith('.whl') else build_source_wheel(p, cache, environment) for p in rest]
    requirements_file(rest, cache, root / 'build-requirements.txt')
    run([sys.executable, '-m', 'pip', 'install', '--no-index', '--no-deps', '--no-build-isolation',
         '--require-hashes', '--disable-pip-version-check', '--no-warn-script-location', '-r', root / 'build-requirements.txt'], root, environment)
    run([sys.executable, '-m', 'pip', 'check'], root, environment, 60)
    verify_installed_runtime()


def verify_installed_runtime():
    # On a clean embedded runtime site-packages does not exist at startup.
    # Imports can cache that missing path as None. Pip creates it in a child;
    # refresh the still-running parent before importing installed packages.
    importlib.invalidate_caches()
    importlib.import_module('py7zr')
    log('Verified SDK extractor imports in the running installer')


def safe_archive_names(entries):
    total = 0
    for entry in entries:
        name = entry.filename
        path = PurePosixPath(name)
        if '\\' in name or ':' in name or path.is_absolute() or '..' in path.parts:
            raise ValueError('Unsafe SDK archive path')
        if any(getattr(entry, name, False) for name in ('is_symlink', 'is_junction')):
            raise ValueError('SDK archive contains a filesystem link')
        total += entry.uncompressed or 0
        if total > 8589934592:
            raise ValueError('SDK archive expansion exceeds limit')


def install_sdk(root):
    import py7zr
    destination = root / 'sdk'
    complete = destination / '.zew-sdk-ready'
    if complete.is_file() and (destination / 'sdk_version').read_text().strip() == '1.0.1':
        log('Reusing verified managed SDK')
        return destination
    if destination.exists():
        raise ValueError('Incomplete SDK destination; move it aside before retrying: ' + str(destination))
    artifacts = [fetch(a, root / 'cache') for a in SDK_ASSETS]
    with tempfile.TemporaryDirectory(prefix='sdk-stage-', dir=root) as temporary:
        stage = Path(temporary)
        for i, archive in enumerate(artifacts):
            folder = stage / str(i); folder.mkdir()
            log('Extracting and verifying ' + archive.name)
            with py7zr.SevenZipFile(archive) as source:
                safe_archive_names(source.list())
                source.extractall(folder)
        versions = list((stage / '0').rglob('sdk_version'))
        if len(versions) != 1 or versions[0].read_text().strip() != '1.0.1':
            raise ValueError('SDK version does not match reviewed release')
        sdk = versions[0].parent
        compilers = list((stage / '1').rglob('x86_64-zephyr-elf-gcc.exe'))
        if len(compilers) != 1:
            raise ValueError('Expected x86 compiler not found in downloaded archive')
        toolchain = compilers[0].parent.parent
        (sdk / 'gnu').mkdir(exist_ok=True)
        shutil.move(str(toolchain), sdk / 'gnu/x86_64-zephyr-elf')
        # Preserve upstream licences in their downloaded distribution locations.
        (sdk / '.zew-sdk-ready').write_text('SDK 1.0.1 / x86_64-zephyr-elf\n')
        sdk.rename(destination)
    return destination


def ensure_source(root, git, lock, environment):
    workspace = root / 'workspace'; workspace.mkdir(exist_ok=True)
    source = workspace / 'zephyr'
    if not source.exists():
        stage = Path(tempfile.mkdtemp(prefix='zephyr-clone-', dir=workspace)) / 'source'
        run([git, '-c', 'core.longpaths=true', '-c', 'http.sslBackend=schannel', 'clone', '--depth', '1', '--single-branch', '--branch', 'v4.4.0',
             'https://github.com/zephyrproject-rtos/zephyr.git', stage], root, environment)
        commit = subprocess.check_output([git, '-C', stage, 'rev-parse', 'HEAD'], text=True, env=environment).strip()
        if commit != lock['zephyr_commit']:
            raise ValueError('Downloaded Zephyr revision does not match the pinned release')
        stage.rename(source)
        stage.parent.rmdir()
    commit = subprocess.check_output([git, '-C', source, 'rev-parse', 'HEAD'], text=True, env=environment).strip()
    if commit != lock['zephyr_commit']:
        raise ValueError('Zephyr checkout does not match reviewed immutable revision; no files changed')
    dirty = subprocess.check_output([git, '-C', source, 'status', '--porcelain', '--untracked-files=no'], text=True, env=environment).strip()
    if dirty:
        raise ValueError('Managed Zephyr source has local edits; preserve it and use a new setup folder')
    if not (workspace / '.west/config').is_file():
        run([sys.executable, '-m', 'west', 'init', '-l', source], workspace, environment)
    return workspace, source


def create_application(root):
    project = root / 'projects/hello_world'
    marker = project / '.zew/project.properties'
    if project.exists():
        if not marker.is_file() or marker.read_text() != 'schema=1\nboard=qemu_x86/atom\nzephyr=4.4.0\nmodules=none\n':
            raise ValueError('Existing application is not this managed starter; it was preserved')
        return project
    project.mkdir(parents=True)
    marker.parent.mkdir()
    marker.write_text('schema=1\nboard=qemu_x86/atom\nzephyr=4.4.0\nmodules=none\n')
    (project / 'src').mkdir()
    (project / 'src/main.c').write_text('#include <zephyr/kernel.h>\n#include <zephyr/sys/printk.h>\n\nint main(void)\n{\n    printk("Hello from Zephyr Eclipse Workbench!\\n");\n    return 0;\n}\n')
    (project / 'CMakeLists.txt').write_text('cmake_minimum_required(VERSION 3.20.5)\nfind_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})\nproject(hello_world)\ntarget_sources(app PRIVATE src/main.c)\n')
    (project / 'prj.conf').write_text('# Application Kconfig options go here.\n')
    (project / '.gitignore').write_text('.zew-build/\n')
    (project / 'README.md').write_text('# Hello Zephyr\n\nZephyr 4.4.0, qemu_x86/atom. This profile compiles without physical hardware.\nUse Eclipse Project > Build Project. Hardware flashing/debugging and QEMU execution are separate steps.\n')
    return project


def write_properties(values, path):
    # Java Properties escaping; paths stay in local installation state, not source.
    def escape(value):
        return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('=', '\\=').replace(':', '\\:')
    temporary = path.with_suffix('.partial')
    temporary.write_text('\n'.join(k + '=' + escape(v) for k, v in values.items()) + '\n', encoding='utf-8')
    temporary.replace(path)


def install_runtime_support(root):
    # Reinstall on retries so older managed environments receive the fix too.
    # Only this managed runtime is changed; keep python313._pth isolated.
    target = root / 'python/sitecustomize.py'
    source = Path(__file__).with_name('managed_sitecustomize.py')
    temporary = target.with_suffix('.partial')
    temporary.write_bytes(source.read_bytes())
    temporary.replace(target)


def check_zephyr_scripts(source, workspace, environment):
    log('Checking isolated Python imports and the starter board metadata')
    run([sys.executable, source / 'scripts/list_boards.py', '--board', 'qemu_x86',
         '--board-root', source, '--soc-root', source, '--cmakeformat', '{NAME};{QUALIFIERS}'],
        workspace, environment, 120)


def cmake_path(value):
    # Windows accepts forward slashes. CMake macros reparse backslashes as
    # escapes (e.g. C:\\Sandbox produces an invalid \\S escape).
    return str(value).replace('\\', '/')


def first_build_command(python, project, build, board, ninja):
    return [python, '-m', 'west', 'build', '--build-dir', cmake_path(build),
            '--board', board, cmake_path(project), '--', '-DZEPHYR_MODULES=',
            '-DCMAKE_MAKE_PROGRAM:FILEPATH=' + cmake_path(ninja),
            '-DPython3_EXECUTABLE:FILEPATH=' + cmake_path(python)]


def setup(root, lock):
    validate_lock(lock)
    if sys.platform != 'win32' or sys.maxsize <= 2**32:
        raise ValueError('This installer profile supports native Windows x64 only; use existing tools on other hosts')
    if not (root / '.zew-managed').is_file() or (root / '.zew-managed').read_text() != 'first-build-win-x64-v1\n':
        raise ValueError('Setup root is not owned by the workbench')
    install_runtime_support(root)
    if shutil.disk_usage(root).free < 5 * 1024**3:
        raise ValueError('Setup requires at least 5 GiB free disk space')
    git = root / 'git/cmd/git.exe'
    cmake = installed_executable(root, lock, 'cmake')
    ninja = installed_executable(root, lock, 'ninja')
    environment = dict(os.environ)
    environment.update(PYTHONIOENCODING='utf-8', PYTHONUNBUFFERED='1', GIT_TERMINAL_PROMPT='0', GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
    environment['PATH'] = os.pathsep.join(map(str, [cmake.parent, ninja.parent, git.parent, root / 'python', root / 'python/Scripts'])) + os.pathsep + environment.get('PATH', '')
    # Prevent unrelated Zephyr/Python variables from selecting another installation.
    for name in ('PYTHONHOME', 'PYTHONPATH', 'VIRTUAL_ENV', 'ZEPHYR_BASE', 'ZEPHYR_SDK_INSTALL_DIR', 'ZEPHYR_TOOLCHAIN_VARIANT', 'BOARD', 'CONF_FILE'):
        environment.pop(name, None)
    log('PHASE 1/5 — Installing pinned Python build packages')
    install_packages(lock, root, environment)
    for executable in (cmake, ninja):
        if not executable.is_file():
            raise ValueError('Installed build executable is missing: ' + str(executable))
    log('PHASE 2/5 — Installing SDK and the x86 compiler only')
    sdk = install_sdk(root)
    environment.update(ZEPHYR_SDK_INSTALL_DIR=cmake_path(sdk), ZEPHYR_TOOLCHAIN_VARIANT='zephyr')
    log('PHASE 3/5 — Obtaining the pinned Zephyr source')
    workspace, source = ensure_source(root, git, lock, environment)
    environment['ZEPHYR_BASE'] = cmake_path(source)
    check_zephyr_scripts(source, workspace, environment)
    log('PHASE 4/5 — Creating Hello World without changing existing source')
    project = create_application(root)
    log('PHASE 5/5 — Building and verifying the first firmware')
    build = project / '.zew-build/first'
    run(first_build_command(sys.executable, project, build, lock['board'], ninja), workspace, environment)
    if not (build / 'zephyr/zephyr.elf').is_file():
        raise ValueError('Build finished without the expected firmware; setup is not ready')
    values = {'python': sys.executable, 'workspace': workspace, 'application': project, 'board': lock['board'],
              'tools.sdk': sdk, 'tools.cmake': cmake, 'tools.ninja': ninja, 'tools.git': git}
    write_properties(values, root / 'environment.properties')
    (root / '.zew-ready').write_text('First build verified; hardware execution has not been tested.\n')
    log('READY — Hello World firmware was built and verified. Eclipse will open src/main.c.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--lock', required=True, type=Path)
    args = parser.parse_args()
    try:
        setup(args.root.resolve(), json.loads(args.lock.read_text(encoding='utf-8')))
    except Exception as error:
        log('FAILED — ' + str(error))
        log('Existing environments were preserved. Retry with the same folder to reuse verified downloads.')
        raise SystemExit(1)



