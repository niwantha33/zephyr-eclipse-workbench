# Validation and release policy

## Automated checks planned

- Build the Eclipse plug-in, feature and p2 repository against a pinned target platform.
- Exercise environment and hardware discovery with supported layouts and missing-tool cases.
- Check paths containing spaces and Unicode, explicit executable selection, and configuration portability.
- Confirm failed processes, cancellation and shared-output contention produce correct results.
- Check project generation, clean/rebuild boundaries and persistent Kconfig fragments.
- Build Hello World across selected Arm, Xtensa and RISC-V targets with recorded versions.
- Include selected multi-image and external-board cases as those capabilities are implemented.
- Test Eclipse indexing and diagnostics using an actual configured application.

Representative Zephyr builds are integration checks. Mocked process results cannot establish compiler, runner or hardware support.

## Manual and physical checks

| Area | Required check |
|---|---|
| Installation | Clean Eclipse install, p2 installation, restart, uninstall and upgrade |
| Existing workspace | Reuse working environment without modifying global settings unexpectedly |
| Editor | Completion, generated macros, application/kernel/driver navigation |
| Configuration | Kconfig change persists after pristine rebuild; Devicetree error opens correct file |
| Build | Source changes rebuild; unchanged build reports no work; failures are visible |
| Flash | Correct board, image/domain, runner, probe and port |
| Serial | Configurable port/baud, reconnect and resource release |
| Debug | Breakpoint in main, step, variable/register inspection, stop and reconnect |
| Multi-core | Explicit image/domain and core selection |
| Portability | Second installation path and fresh-machine instructions |

Record date, repository commit, Eclipse/CDT/Java versions, Zephyr commit, West/Python/SDK/tool versions, application/configuration, exact board revision, connection, probe, commands, results and limitations.

## CI and privacy

Keep the repository and development artefacts private until the owner explicitly authorises publication. Do not enable GitHub Pages, external upload services, or a public update site during private development.

GitHub Actions jobs must have timeouts, bounded matrices, appropriate cache keys, minimal permissions and no unreviewed secret exposure. Choose an initial matrix after confirming available runner capacity and budget.

## Release gates

- Milestone acceptance criteria in ROADMAP.md pass.
- Evidence matrix distinguishes discovered, build, flash, run and debug.
- Fresh install and upgrade have been checked.
- Applicable [quality targets](QUALITY.md) have recorded results; competitor claims cite actual comparable measurements.
- [Update and recovery checks](UPDATES.md) pass for the release scope, including interrupted download, incompatible plan, restart deferral and preserved project pins.
- Original material has a root Apache-2.0 licence. Third-party licences, notices, source availability, runtime exceptions and redistribution terms are resolved for each included exact version.
- A resolved dependency inventory/SBOM covers p2, Maven, Python, native tools, templates, firmware libraries and any submodules.
- The core workflow passes without proprietary mandatory utilities, non-commercial restrictions, trials or paid activation.
- Board operations/features requiring proprietary components are labelled and excluded from the compliant workflow.
- Dependency updates recheck licence/provenance changes; unreviewed or disallowed changes block automatic installation.
- Documentation matches actual UI and functionality.
- No local paths, credentials, downloaded SDKs or Eclipse workspace metadata are committed.
- Public publication remains a separate owner decision.

## Update and quality validation

Record component inventories before and after maintenance. Verify idle gating, active jobs, unsaved editors, offline use, tampered downloads, disk/permission failures, recovery and project-file preservation. Treat p2 history, product rollback, environment rollback and project/configuration restoration as separate checks.

Profile UI responsiveness and measure workspace import, cached board search and warm-build overhead on a declared reference machine. Benchmark CLion and a named VS Code extension configuration on equivalent inputs before publishing superiority claims.

## Licence validation

Review the actual shipped/deployed files and exact versions. A permissive root licence does not approve every transitive package. Keep separate records for tool execution, redistributed tools, linked runtime/firmware code and generated templates. Preserve licence/NOTICE files and provide source where the applicable licence requires it.

Submodule validation includes exact gitlinks, nested dependencies, upstream URLs, licences and modifications. No actual submodule is currently selected. No third-party licence audit has passed yet.

## Current status

The first development slice has 29 passing core/process checks and a successful Tycho package build against Eclipse 4.41. A package-inspection script checks JAR integrity, original licence text, extension classes, feature references and p2 metadata. See BUILD_REPORT.md for exact versions and limitations.

A manual GitHub Actions packaging workflow is provided but has not been run. Desktop installation, Windows runtime, actual firmware builds through the UI, hardware, update/recovery behaviour and complete dependency/licence review remain unverified. The earlier sections define remaining release checks rather than successful results.
