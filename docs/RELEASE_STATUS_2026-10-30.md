<!-- SPDX-License-Identifier: Apache-2.0 -->
# Release status and evidence — 2026-10-30

**State:** development preview, **not a complete public production release**.
Published GitHub Release: [v0.1.21-preview.1](https://github.com/niwantha33/zephyr-eclipse-workbench/releases/tag/v0.1.21-preview.1).
Current main is newer than that release. Do not use the release tag as proof of subsequently merged functionality.

## Evidence policy

The [development CI run 41017546633](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41017546633)
completed both the Ubuntu p2/UI package job and the native Windows firmware-matrix job successfully.
The [main update publisher run 41029397946](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/41029397946) also succeeded.
Those runs predate the configuration diagnosis changes proposed alongside this file.
They are automated tests, **not** proof of successful clean-install, flash, debug, trace or fault analysis on every physical target.

| Workstream | Current concrete implementation | Release gate still outstanding |
| --- | --- | --- |
| Project new/import | ZephyrProjectWizard, ZephyrImportWizard, ZephyrApplicationDiscovery | Fresh-machine UX; parent-folder/ambiguous application edge cases |
| Managed host tools | SetupWizard, bundled tool scripts, BuildHostRequirements | Interrupted downloads and cross-host recovery audit |
| Board and exact build | ProjectProfile, ZephyrCoreBuildProvider, TargetOperations | More external roots, profile fragments and multi-image cases |
| Firmware outputs | BuildArtifacts, ZephyrArtifactsHandler | Visibility and validation across heterogeneous image types |
| Flash/debug | ZephyrNativeDebugDelegate, ZephyrDebugProcesses, DebugPorts | Multi-backend physical validation; repeated stop/relaunch cycles; cross-process probe lock |
| Configuration | ConfigurationStudioView, KconfigInspector, ConfigurationStudioFiles | Validate effective Kconfig editing/refresh in live Eclipse |
| Devicetree/pins | DevicetreeHardware, DevicetreeParameters, ChipPinCanvas | Authoritative package geometry, typed binding validation, safe overlay workflow |
| Configuration diagnosis | ConfigurationDiagnosis and ConfigurationDiagnosisTests on this branch | PR CI, real-build fixtures, runtime init/driver evidence and UI smoke |
| SoC registers | SocRegistersView, SvdDevice | Exact-device SVD matching, side-effect safety and debugger hardware tests |
| RTOS tracing | ZephyrTraceHandler, tools/rtos_analysis | Validated thread/CPU/stack measurements, loss accounting, broader devices |
| Logs/fault/core dump | Basic build/debug console capture | Crash import, ELF identity, architecture-specific decoding, export |
| Safe peripheral tests | Existing examples and manual hardware workflows | Isolated GPIO/ADC/I²C test wizard, controlled execution and reports |
| Share/compare investigations | No integrated portable evidence package verified | Redaction, offline import, configuration and build comparison |
| First-use demo | Existing examples/rtos_analysis_demo | Recorded, reproducible failure-to-source demo with labelled provenance |
| Installation and updates | p2 site, GitHubUpdates, UpdateController, CI publisher | Complete clean-system install/update/rollback tests and licensing audit |
| Platforms | Linux packaging/UI CI; Windows firmware CI | Physical Windows/Linux IDE acceptance; no macOS validation |
| Licensing | Root Apache-2.0; docs/LICENSING.md, docs/DEPENDENCIES.md | Exact transitive redistribution review/SBOM before bundled distribution |

## This change: configuration diagnosis

A **read-only Diagnose** control in native Configuration Studio checks the currently
selected project's real generated .config and zephyr.dts and optional selected node.
It reports whether evidence is confirmed, merely possible, or missing. Checks cover
unbuilt configurations, a source prj.conf newer than the generated configuration,
requested/effective Kconfig differences, missing selected nodes, disabled device nodes
and disabled ancestor buses/controllers. No invented driver/pin mapping and no
automatic edits. The Insights tab shows the report.

**Reproduce without hardware:** import a Zephyr app, select its exact board/profile,
build it, open Zephyr Configuration Studio and choose **Diagnose**. From Hardware,
select an ADC or other node and choose **Diagnose selected**. Compare an existing
request in prj.conf with the last generated .config. Modify an input and rerun
diagnosis *before rebuilding* to see the explicit stale-snapshot finding.
Use **Build & validate** to re-evaluate actual Zephyr semantics. The report itself
is not a substitute for a Zephyr build or physical peripheral test.


## MCU Register Viewer reuse decision

The separate niwantha33/mcu_register_viewer repository declares a **proprietary,
all-rights-reserved licence** in LICENSE.txt. Its VS Code implementation and
bundled data therefore cannot simply be copied or redistributed under the
Workbench's Apache-2.0 terms. No source from that repository is imported in
this change. A separate explicit licence grant or appropriately licensed
reimplementation and review of SVD/data provenance is required before reuse.
The independent existing Eclipse SvdDevice/SocRegistersView implementation
remains the current baseline.

## Release decisions and acceptance

- [ ] Current PR automated core, Tycho p2, native Linux UI and Windows firmware CI all pass.
- [ ] Installer extracted and clean-install/upgrade/rollback verified on supported hosts.
- [ ] Supported probes show repeatable real flash/debug sessions including termination.
- [ ] ADC Devicetree overlay, source, flash and measured behaviour verified on named hardware.
- [ ] Trace and fault fixtures validated against known captured evidence.
- [ ] Investigation export and safe peripheral tests implemented.
- [ ] Matching screenshots, fresh-install guide, release assets, hashes and dependency notices prepared.
- [ ] Unverified targets and unresolved capabilities clearly marked in release notes.
- [ ] Approvals and release publication criteria met.

These unchecked items are not completed merely because the underlying
platform builds. Do not label the product production-ready until the gates pass.
