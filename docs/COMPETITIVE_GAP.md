<!-- SPDX-License-Identifier: Apache-2.0 -->
# Competitive gaps — Phase 0

G0 status: **NOT PASSED**. This is a documentary assessment. Competitors were NOT installed and run; screenshots, versions and exact build fixtures are still needed.

| Alternative | Documented capability | Gap verdict |
| --- | --- | --- |
| [Ac6 Workbench for Zephyr](https://marketplace.visualstudio.com/items?itemName=Ac6.zephyr-workbench) | Complete IDE flow, Kconfig, DT, memory, SBOM, AI/MCP | No gap for generic IDE/agent/metadata inspection |
| [Zephyr west spdx](https://docs.zephyrproject.org/latest/develop/west/zephyr-cmds.html) | SPDX 3 source-to-compiled-output/tool graph, per-image sysbuild | No gap for raw SBOM/build provenance |
| [Nordic west ncs-sbom](https://nrfconnectdocs.nordicsemi.com/ncs/latest/nrf/scripts/west_commands/sbom/README.html) | Ninja dependency and archive inputs | No gap for basic source inventory |
| [Zephyr tracing / Percepio](https://docs.zephyrproject.org/latest/services/tracing/index.html) | Existing recorder and viewer options | Do not build new trace viewer |
| Zephyr IDE, nRF Connect, STM32CubeIDE VS Code | Versions/feature claims not hands-on verified | Open |

## Six required scenarios (not yet benchmarked)
1. Kconfig/DT/driver explanation: Ac6 DT Doctor and existing Workbench diagnosis overlap; prove new causal provenance before implementation.
2. Cortex-M fatal log/core: Workbench has a synthetic tested parser; compare with upstream core dump/GDB and matching-ELF behaviour.
3. Working/failing build: existing Workbench ZIP can compare Kconfig; full sysbuild graph comparison remains to be tested.
4. CVE on one build: upstream SBOM records components, not automatically configuration-specific exploitability. Curate counterexamples and prevent false NOT_AFFECTED.
5. Upgrade-impact: uniqueness not established. Compare real version-pair port and trial build failures.
6. Offline investigation: Workbench already has a ZIP; evaluate reproducibility, provenance and privacy instead of recreating archive basics.

Next: pin and install competitor versions on clean Windows and Linux, apply identical six fixtures, record outputs and failures, gather evidence; confirm or revise scope. No G0 approval yet.
