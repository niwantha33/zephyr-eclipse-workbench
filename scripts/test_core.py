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
# Reuse the real editor's JFace-independent index and completion engine in CI.
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/MemoryProfilerJson.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ThreadTimelineReport.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ThreadCpuLoad.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/ContentionReport.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/OfflineTraceAnalysisService.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/OfflineTraceViewModels.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/MemoryTraceReport.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/TracepointReport.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlDocumentModel.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlTreeProjection.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlFlowGraph.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/DtsGraphModel.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/UmlClassModel.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlSourceLoader.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlPythonLaunch.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlBindingIntelligence.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlHardwareModel.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlTwisterModel.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlDiagnosticModel.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlScalarEdit.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/YamlPatchValidator.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/MemoryProfilerLayout.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/MemoryProfilerReportGuard.java']
sources += [root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/DevicetreeIndex.java',
            root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/DevicetreeAssist.java',
            root / 'plugins/io.github.niwantha33.zew.ui/src/io/github/niwantha33/zew/ui/DevicetreeEditorDiagnostics.java']
subprocess.run([tool('javac'), '--release', '21', '--add-modules', 'jdk.httpserver', '-encoding', 'UTF-8', '-d', str(out), *map(str, sources)], check=True, cwd=root)
subprocess.run([tool('java'), '-cp', str(out), 'CoreTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ZephyrSelectionIdentityTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'EmbeddedHubBoardCatalogueTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ZephyrReferenceCatalogTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '--add-modules', 'jdk.httpserver', '-cp', str(out), 'UpdateTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'SetupTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ProjectFilesTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ApplicationDiscoveryTests'], check=True, cwd=root, timeout=90)


subprocess.run([tool('java'), '-cp', str(out), 'EditorTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'MemoryProfilerJsonTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ThreadTimelineReportTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ThreadCpuLoadTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ContentionReportTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'OfflineTraceAnalysisServiceTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'MemoryTraceReportTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'TracepointReportTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlDocumentModelTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlTreeProjectionTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlFlowGraphTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DtsGraphModelTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'UmlClassModelTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlSourceLoaderTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlBindingIntelligenceTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlHardwareModelTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlTwisterModelTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlDiagnosticModelTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'YamlScalarEditTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'MemoryProfilerLayoutTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'MemoryProfilerReportGuardTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'io.github.niwantha33.zew.ui.DevicetreeAssistTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'io.github.niwantha33.zew.ui.DevicetreeEditorDiagnosticsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ProjectTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildHostRequirementsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildReliabilityTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildFailureInvestigatorTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildFailureLogsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildProfileTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BuildProfileChoicesTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ConfigurationStudioTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ConfigurationStudioDiagnosticsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'StudioEvidenceInspectorTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ConfigurationEvidenceReceiptTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ConfigurationFailureEvidenceTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'KconfigTraceEvidenceTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'GuidedOverlaySaveTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DevicetreeSourceTraceTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DevicetreeOverlaySupportTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'BeginnerHardwareGuideTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DevicetreeMemoryMapTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'FocusedConfigurationModelTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'TargetOperationsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'EspressifFlashSafetyTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DebugServerDiagnosticsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'EspressifOpenOcdTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'OpenOcdRegistryTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'DebugPortsTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DebugBackendTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'DemoConfigurationCompatibilityTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'OpenOcdThreadCheckTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'DebugConsoleOutputTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'TraceConsoleCapabilitiesTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'RttTcpReaderTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'DebugSessionSelectionTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'SvdDeviceTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'SvdAutoDiscoveryTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'SvdTargetEvidenceTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'SvdPartCompatibilityTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'GitHubSvdProvisionerTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'VendorSvdProvisionerTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'ConfigurationDiagnosisTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'InvestigationBundleTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'PhysicalOperationLockTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'FaultLogAnalysisTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'DebugSourcePathsTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'FlashDebugBuildTests'], check=True, cwd=root, timeout=90)

subprocess.run([tool('java'), '-cp', str(out), 'GpioUsageRegressionTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'RegisterHistoryTests'], check=True, cwd=root, timeout=90)
subprocess.run([tool('java'), '-cp', str(out), 'RegisterSparklineTests'], check=True, cwd=root, timeout=90)
