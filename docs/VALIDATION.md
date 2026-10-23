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
- Distribution licence, third-party notices and binary redistribution terms are resolved.
- Documentation matches actual UI and functionality.
- No local paths, credentials, downloaded SDKs or Eclipse workspace metadata are committed.
- Public publication remains a separate owner decision.

## Current status

No plug-in, CI job or automated test has been implemented or run. These documents define planned checks, not successful test results.
