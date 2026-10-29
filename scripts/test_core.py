#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise Java core and local tools inventory; packaging enables requirement tests."""
from pathlib import Path
import os
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
os.environ['ZEW_TEST_PYTHON'] = sys.executable
subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tests/tools', '-v'], check=True, cwd=root, timeout=90)
out = root / 'build-core-tests'
out.mkdir(exist_ok=True)
suffix = '.exe' if os.name == 'nt' else ''
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    value = str(Path(java_home) / 'bin' / (name + suffix)) if java_home else shutil.which(name)
    if not value or not Path(value).is_file():
        raise SystemExit(f'{name} not found. Install an open-source JDK 21 and set JAVA_HOME.')
    return value

sources = sorted((root / 'plugins/io.github.niwantha33.zew.core/src').rglob('*.java'))
sources += sorted((root / 'tests/core').glob('*.java'))
subprocess.run([tool('javac'), '--release', '21', '--add-modules', 'jdk.httpserver', '-encoding', 'UTF-8', '-d', str(out), *map(str, sources)], check=True, cwd=root)
subprocess.run([tool('java'), '-cp', str(out), 'CoreTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '--add-modules', 'jdk.httpserver', '-cp', str(out), 'UpdateTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'SetupTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ProjectFilesTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ApplicationDiscoveryTests'], check=True, cwd=root, timeout=90)


subprocess.run([tool('java'), '-cp', str(out), 'EditorTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ProjectTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildHostRequirementsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildReliabilityTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ConfigurationStudioTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'TargetOperationsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'EspressifFlashSafetyTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DebugServerDiagnosticsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'EspressifOpenOcdTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'OpenOcdRegistryTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'DebugPortsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DebugBackendTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'DemoConfigurationCompatibilityTests'], check=True, cwd=root, timeout=90)
