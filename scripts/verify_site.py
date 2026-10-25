#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check packaged p2 metadata, original bundle contents and archive integrity."""
from pathlib import Path
import argparse
import tempfile
import hashlib
import xml.etree.ElementTree as ET
import zipfile
import json

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--site', type=Path)
parser.add_argument('--archive', type=Path)
args = parser.parse_args()
version = ET.parse(root / 'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
archive = args.archive or root / 'releng/repository/target' / f'workbench-repository-{version}.zip'
temporary = tempfile.TemporaryDirectory(prefix='zew-verify-site-')
site = args.site or Path(temporary.name)
if args.site is None:
    # Verify the distributable itself, avoiding stale exploded build output.
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if not (site / name).resolve().is_relative_to(site.resolve()):
                raise SystemExit('Unsafe archive path.')
        z.extractall(site)
expected = {'io.github.niwantha33.zew.core', 'io.github.niwantha33.zew.ui'}
bundles = list((site / 'plugins').glob('*.jar'))
if {p.name.split('_', 1)[0] for p in bundles} != expected:
    raise SystemExit('Unexpected bundled dependencies or missing plug-ins.')
for p in bundles:
    with zipfile.ZipFile(p) as z:
        if z.testzip() is not None:
            raise SystemExit(f'Corrupt archive: {p.name}')
        if z.read('LICENSE').strip() != (root / 'LICENSE').read_bytes().strip():
            raise SystemExit(f'Missing or incorrect licence: {p.name}')
        if not any(n.endswith('.class') for n in z.namelist()):
            raise SystemExit(f'No compiled classes: {p.name}')
        if '.ui_' in p.name:
            if 'tools/inventory.py' not in z.namelist():
                raise SystemExit('Tools inventory helper missing.')
            compile(z.read('tools/inventory.py'), 'tools/inventory.py', 'exec')
            compile(z.read('tools/setup.py'), 'tools/setup.py', 'exec')
            compile(z.read('tools/managed_sitecustomize.py'), 'tools/managed_sitecustomize.py', 'exec')
            for helper in ('setup.py', 'managed_sitecustomize.py'):
                if z.read('tools/' + helper) != (root / 'plugins/io.github.niwantha33.zew.ui/tools' / helper).read_bytes():
                    raise SystemExit('Packaged helper differs from verified source: ' + helper)
            for helper in ('hardware_catalog.py', 'editor_settings.py', 'board_tools.py', 'board_dependencies.py', 'dependency_engine.py', 'build_paths.py', 'target_metadata.py'):
                compile(z.read('tools/' + helper), 'tools/' + helper, 'exec')
                if z.read('tools/' + helper) != (root / 'plugins/io.github.niwantha33.zew.ui/tools' / helper).read_bytes():
                    raise SystemExit('Packaged helper differs from source: ' + helper)
            compiler_lock = json.loads(z.read('tools/sdk-toolchains-windows-1.0.1.json'))
            if compiler_lock.get('schema') != 1 or compiler_lock.get('sdk_version') != '1.0.1' or compiler_lock.get('host') != 'windows-x86_64':
                raise SystemExit('Invalid compiler dependency lock')
            for name, entry in compiler_lock['assets'].items():
                if entry['file'] != 'toolchain_gnu_windows-x86_64_' + name + '.7z' or len(entry['sha256']) != 64 or entry['size'] <= 0:
                    raise SystemExit('Malformed compiler dependency: ' + name)
            if z.read('tools/sdk-toolchains-windows-1.0.1.json') != (root / 'plugins/io.github.niwantha33.zew.ui/tools/sdk-toolchains-windows-1.0.1.json').read_bytes():
                raise SystemExit('Packaged SDK toolchain lock differs from source')
            board_lock = json.loads(z.read('tools/esp32s3-python-lock.json'))
            if board_lock['platform'] != 'win_amd64' or board_lock['python'] != '3.13':
                raise SystemExit('Invalid ESP32-S3 Python lock')
            if z.read('tools/esp32s3-python-lock.json') != (root / 'plugins/io.github.niwantha33.zew.ui/tools/esp32s3-python-lock.json').read_bytes():
                raise SystemExit('Board dependency lock differs from source')
            signing_lock = json.loads(z.read('tools/mcuboot-python-lock.json'))
            if (signing_lock['schema'], signing_lock['python'], signing_lock['platform']) != (1, '3.13', 'win_amd64'):
                raise SystemExit('Invalid MCUboot dependency lock')
            for package in signing_lock['packages']:
                if len(package['sha256']) != 64 or package['size'] <= 0 or not package['url'].startswith('https://files.pythonhosted.org/'):
                    raise SystemExit('Malformed MCUboot dependency: ' + package['name'])
            if z.read('tools/mcuboot-python-lock.json') != (root / 'plugins/io.github.niwantha33.zew.ui/tools/mcuboot-python-lock.json').read_bytes():
                raise SystemExit('Packaged MCUboot dependency lock differs from verified source')
            lock = json.loads(z.read('tools/setup-lock.json'))
            if lock['profile'] != 'first-build-win-x64' or any(p['review'] != 'approved-build-use' for p in lock['packages']):
                raise SystemExit('Setup profile or package review is incomplete.')
            if not z.read('tools/setup-licences.txt').strip():
                raise SystemExit('Setup licence records missing.')
            for name in ('SetupWizard', 'SetupStartup', 'ZephyrProjectBuilder', 'ZephyrProjectWizard', 'ZephyrImportWizard', 'ProjectActionHandler', 'ProjectContext', 'CdtEditor', 'ZephyrProjectPropertyPage', 'ZephyrLaunchDelegate', 'ZephyrLaunchTabs'):
                if 'io/github/niwantha33/zew/ui/' + name + '.class' not in z.namelist():
                    raise SystemExit('Beginner setup class missing: ' + name)
            # Guard against the exact OSGi FileLocator failure on Windows:
            # a single .py jar entry can be extracted without any siblings.
            # The builder must bundle its dependency set as one versioned unit.
            board_class = z.read('io/github/niwantha33/zew/ui/BoardTools.class')
            for required in (b'.zew-board-tools', b'board_dependencies.py', b'board_tools.py',
                             b'hardware_catalog.py'):
                if required not in board_class:
                    raise SystemExit('Board-tools sibling staging missing from compiled builder')
            for helper in ('board_dependencies.py', 'dependency_engine.py', 'build_paths.py', 'board_tools.py', 'hardware_catalog.py', 'target_metadata.py',
                           'setup.py', 'managed_sitecustomize.py',
                           'sdk-toolchains-windows-1.0.1.json', 'esp32s3-python-lock.json',
                           'mcuboot-python-lock.json'):
                if 'tools/' + helper not in z.namelist():
                    raise SystemExit('Required bundled board helper missing: ' + helper)
            if 'io/github/niwantha33/zew/ui/ToolsDialog.class' not in z.namelist():
                raise SystemExit('Tools manager dialog missing.')
            plugin = ET.fromstring(z.read('plugin.xml'))
            for page in plugin.findall("./extension[@point='org.eclipse.ui.preferencePages']/page"):
                if page.attrib['class'].replace('.', '/') + '.class' not in z.namelist():
                    raise SystemExit('Preference page class missing.')
            for point in ('org.eclipse.ui.newWizards', 'org.eclipse.ui.importWizards'):
                registrations = plugin.findall("./extension[@point='" + point + "']/wizard")
                if not any(w.attrib.get('class') in ('io.github.niwantha33.zew.ui.ZephyrProjectWizard', 'io.github.niwantha33.zew.ui.ZephyrImportWizard') for w in registrations):
                    raise SystemExit('New/Import Zephyr wizard not registered for ' + point)
            for point, cls in (
                    ('org.eclipse.ui.propertyPages', 'ZephyrProjectPropertyPage'),
                    ('org.eclipse.debug.core.launchDelegates', 'ZephyrLaunchDelegate'),
                    ('org.eclipse.debug.core.launchDelegates', 'ZephyrNativeDebugDelegate'),
                    ('org.eclipse.debug.ui.launchConfigurationTabGroups', 'ZephyrLaunchTabs')):
                registrations = plugin.findall("./extension[@point='" + point + "']/*")
                if not any(item.attrib.get('class', item.attrib.get('delegate', '')).endswith('.' + cls)
                           for item in registrations):
                    raise SystemExit('Missing native Eclipse registration: ' + cls)
            types = plugin.findall("./extension[@point='org.eclipse.debug.core.launchConfigurationTypes']/launchConfigurationType")
            if not any(t.attrib.get('id') == 'io.github.niwantha33.zew.ui.launchType'
                       and set(t.attrib.get('modes', '').split(',')) == {'run', 'debug'} for t in types):
                raise SystemExit('Zephyr launch type must support real Run and Debug modes')
            delegates = plugin.findall("./extension[@point='org.eclipse.debug.core.launchDelegates']/launchDelegate")
            for mode, classname in (('run', 'ZephyrLaunchDelegate'), ('debug', 'ZephyrNativeDebugDelegate')):
                if not any(d.attrib.get('modes') == mode and
                           d.attrib.get('delegate', '').endswith('.' + classname)
                           for d in delegates):
                    raise SystemExit('Missing Zephyr ' + mode + ' launch delegate')
            for command_id, handler_class in (('build', 'ZephyrBuildHandler'),
                                              ('rebuild', 'ZephyrBuildHandler'),
                                              ('artifacts', 'ZephyrArtifactsHandler'),
                                              ('debugTools', 'DebugToolsHandler'),
                                              ('quickStart', 'ZephyrQuickStartHandler')):
                registered = plugin.findall("./extension[@point='org.eclipse.ui.handlers']/handler")
                if not any(item.attrib.get('commandId') == 'io.github.niwantha33.zew.' + command_id
                           and item.attrib.get('class', '').endswith('.' + handler_class)
                           for item in registered):
                    raise SystemExit('Managed project workflow handler missing: ' + command_id)
            for cmd in ('flash', 'debug'):
                commands = plugin.findall("./extension[@point='org.eclipse.ui.commands']/command")
                if not any(c.attrib.get('id') == 'io.github.niwantha33.zew.' + cmd for c in commands):
                    raise SystemExit('Missing ' + cmd + ' Eclipse command')
                if 'icons/' + cmd + '.png' not in z.namelist():
                    raise SystemExit('Missing packaged ' + cmd + ' toolbar icon')
            shortcut = plugin.find("./extension[@point='org.eclipse.debug.ui.launchShortcuts']/shortcut[@id='io.github.niwantha33.zew.ui.debugShortcut']")
            if shortcut is None or shortcut.attrib.get('modes') != 'debug':
                raise SystemExit('Native Eclipse Debug shortcut missing or advertises a flash/run mode')
            if shortcut.find("configurationType[@id='io.github.niwantha33.zew.ui.launchType']") is None:
                raise SystemExit('Debug shortcut is not associated with the managed CDT launch type')
            if shortcut.find("contextualLaunch/enablement") is None:
                raise SystemExit('Debug shortcut lacks project-sensitive enablement')
            toolbar = plugin.findall("./extension[@point='org.eclipse.ui.menus']/menuContribution/toolbar/command")
            if any(item.attrib.get('commandId') == 'io.github.niwantha33.zew.debug' for item in toolbar):
                raise SystemExit('Duplicate custom Debug toolbar button; use native Eclipse Debug')
            for cls in ('ZephyrNativeDebugDelegate', 'ZephyrDebugHandler',
                        'ZephyrDebugShortcut', 'ZephyrProjectTester',
                        'SelectedProject', 'ZephyrBuildHandler', 'ZephyrArtifactsHandler',
                        'DebugToolsHandler', 'SharedDebugTools',
                        'ZephyrChipMismatchRecovery',
                        'ZephyrQuickStartHandler'):
                if 'io/github/niwantha33/zew/ui/' + cls + '.class' not in z.namelist():
                    raise SystemExit('Missing native Debug class: ' + cls)
            if any(item.attrib.get('commandId', '').endswith('.selectCpu')
                   for item in plugin.findall(".//command")):
                raise SystemExit('Obsolete vendor-specific CPU command still registered')
            for handler in plugin.findall("./extension[@point='org.eclipse.ui.handlers']/handler"):
                if handler.attrib['class'].replace('.', '/') + '.class' not in z.namelist():
                    raise SystemExit('Command handler class missing.')
    print('Verified bundle:', p.name)
for name, entry in [('content.jar', 'content.xml'), ('artifacts.jar', 'artifacts.xml')]:
    with zipfile.ZipFile(site / name) as z:
        if z.testzip() is not None:
            raise SystemExit(f'Corrupt metadata: {name}')
        ET.fromstring(z.read(entry))
features = list((site / 'features').glob('*.jar'))
if len(features) != 1:
    raise SystemExit('Expected one installable feature.')
with zipfile.ZipFile(features[0]) as z:
    feature = ET.fromstring(z.read('feature.xml'))
    if {p.attrib['id'] for p in feature.findall('plugin')} != expected:
        raise SystemExit('Feature does not reference the two original bundles.')
    if z.read('LICENSE').strip() != (root / 'LICENSE').read_bytes().strip():
        raise SystemExit('Feature licence missing or incorrect.')
with zipfile.ZipFile(archive) as z:
    if z.testzip() is not None or 'p2.index' not in z.namelist():
        raise SystemExit('Invalid update-site ZIP.')
print('Verified p2 metadata and feature.')
print('Update-site SHA-256:', hashlib.sha256(archive.read_bytes()).hexdigest())
print('This checks packaging; it does not establish desktop installation or hardware support.')



