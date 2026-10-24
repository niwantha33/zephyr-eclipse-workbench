# SPDX-License-Identifier: Apache-2.0
"""Add a reviewed ESP32-S3 compiler without replacing an existing SDK."""
import argparse
import importlib.util
import json
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location('zew_setup', Path(__file__).with_name('setup.py'))
setup = importlib.util.module_from_spec(spec); spec.loader.exec_module(setup)
TOOLCHAIN = 'xtensa-espressif_esp32s3_zephyr-elf'
ASSET = {'file': 'toolchain_gnu_windows-x86_64_' + TOOLCHAIN + '.7z', 'size': 47622966,
         'sha256': 'a3af742531382e94a378cb3d5f36c11150f54832037ab399ff765c830d5a5901'}
ASSET['url'] = 'https://github.com/zephyrproject-rtos/sdk-ng/releases/download/v1.0.1/' + ASSET['file']
HAL_REVISION = 'b7953b8019361d09e613f7011d2ccc41b984d087'


def toolchain_for(board):
    parts = board.split('/')
    return TOOLCHAIN if len(parts) >= 2 and parts[1] == 'esp32s3' else None


def verify(compiler):
    subprocess.run([str(compiler), '--version'], check=True, timeout=30, stdout=sys.stdout, stderr=sys.stderr)


def install_compiler(root, sdk):
    if (sdk / 'sdk_version').read_text().strip() != '1.0.1':
        raise ValueError('ESP32-S3 automatic repair requires the managed SDK 1.0.1')
    destination = sdk / 'gnu' / TOOLCHAIN
    compiler = destination / 'bin' / (TOOLCHAIN + '-gcc.exe')
    if compiler.is_file():
        setup.log('Checking existing ESP32-S3 compiler'); verify(compiler); return compiler
    if destination.exists():
        raise ValueError('Incomplete ESP32-S3 compiler directory was preserved: ' + str(destination))
    (root / 'cache').mkdir(exist_ok=True)
    archive = setup.fetch(ASSET, root / 'cache')
    import py7zr
    with tempfile.TemporaryDirectory(prefix='.zew-s3-stage-', dir=sdk) as temporary:
        stage = Path(temporary)
        setup.log('Extracting verified ESP32-S3 compiler (working x86 SDK is preserved)')
        with py7zr.SevenZipFile(archive) as source:
            setup.safe_archive_names(source.list()); source.extractall(stage)
        compilers = list(stage.rglob(TOOLCHAIN + '-gcc.exe'))
        if len(compilers) != 1 or compilers[0].parent.name != 'bin' or compilers[0].parent.parent.name != TOOLCHAIN:
            raise ValueError('Unexpected ESP32-S3 toolchain archive layout')
        verify(compilers[0])
        toolchain = compilers[0].parent.parent
        (toolchain / '.zew-toolchain-proof').write_text(ASSET['sha256'] + '\n')
        destination.parent.mkdir(exist_ok=True)
        toolchain.rename(destination)
    setup.log('READY — ESP32-S3 compiler installed and --version verified')
    return compiler


def ensure_hal(root, workspace, git):
    source = workspace / 'zephyr'
    revision = subprocess.check_output([git, '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != '684c9e8f32e4373a21098559f748f06915f950c9':
        raise ValueError('Automatic ESP32-S3 module repair requires the pinned Zephyr 4.4.0 checkout')
    destination = workspace / 'modules/hal/espressif'
    if destination.exists():
        actual = subprocess.check_output([git, '-C', str(destination), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != HAL_REVISION: raise ValueError('Existing Espressif module uses another revision; it was preserved')
        setup.log('Reusing pinned Espressif HAL source'); return
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.zew-hal-stage-', dir=destination.parent) as temporary:
        stage = Path(temporary) / 'source'; stage.mkdir()
        def run(args):
            setup.log('Obtaining pinned Espressif HAL source')
            subprocess.run([git, '-c', 'core.longpaths=true', *args], cwd=stage, check=True, timeout=600)
        run(['init'])
        run(['remote', 'add', 'origin', 'https://github.com/zephyrproject-rtos/hal_espressif.git'])
        run(['-c', 'http.sslBackend=schannel', 'fetch', '--depth', '1', 'origin', HAL_REVISION])
        run(['checkout', '--detach', 'FETCH_HEAD'])
        actual = subprocess.check_output([git, '-C', str(stage), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != HAL_REVISION: raise ValueError('Espressif HAL source revision mismatch')
        stage.rename(destination)
    setup.log('READY — pinned Espressif HAL source installed; no west blobs fetch was run')


def install_python_tools(root):
    lock = json.loads(Path(__file__).with_name('esp32s3-python-lock.json').read_text())
    if sys.version_info[:2] != (3, 13): raise ValueError('ESP32-S3 Python tools require the managed Python 3.13 runtime')
    destination = root / 'board-python/esp32s3'
    proof = destination / '.zew-ready'
    if not proof.is_file():
        if destination.exists(): raise ValueError('Incomplete board Python environment preserved: ' + str(destination))
        archives = [setup.fetch(package, root / 'cache') for package in lock['packages']]
        destination.parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.zew-esptool-stage-', dir=destination.parent) as temporary:
            stage = Path(temporary) / 'packages'
            # Dependencies are already pinned and verified; build source only with the
            # managed setuptools/wheel, without network or build-environment installs.
            subprocess.run([sys.executable, '-m', 'pip', 'install', '--no-index', '--no-deps', '--no-build-isolation',
                '--disable-pip-version-check', '--target', str(stage), *map(str, archives)], check=True, timeout=600)
            subprocess.run([sys.executable, '-c', "import sys;sys.path.insert(0,sys.argv[1]);import esptool;esptool.main(['version'])", str(stage)], check=True, timeout=30)
            (stage / '.zew-ready').write_text('esptool 5.2.0 / pinned Windows CPython 3.13 dependencies\n')
            stage.rename(destination)
    site = root / 'python/Lib/site-packages'
    pth = site / 'zew-esp32s3.pth'
    value = str(destination) + '\n'
    if pth.exists() and pth.read_text() != value: raise ValueError('Conflicting board Python mapping preserved')
    pth.write_text(value)
    subprocess.run([sys.executable, '-m', 'esptool', 'version'], check=True, timeout=30)
    setup.log('READY — isolated esptool 5.2.0 and dependencies verified')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True); parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True); parser.add_argument('--git', required=True)
    parser.add_argument('--board', required=True)
    args = parser.parse_args()
    if toolchain_for(args.board) is None: return
    if os.name != 'nt': raise ValueError('Automatic ESP32-S3 compiler installation currently supports Windows x64 only')
    install_compiler(args.root.resolve(), args.sdk.resolve())
    install_python_tools(args.root.resolve())
    ensure_hal(args.root.resolve(), args.workspace.resolve(), args.git)


if __name__ == '__main__': main()
