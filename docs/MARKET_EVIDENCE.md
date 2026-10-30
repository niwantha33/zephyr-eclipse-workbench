<!-- SPDX-License-Identifier: Apache-2.0 -->
# Market evidence — Phase 0, 2026-10-30

These are documentary checks, NOT hands-on competitor benchmarks.

1. **Basic IDE features already commoditised.** [Zephyr's Ac6 Workbench reference](https://docs.zephyrproject.org/latest/develop/tools/workbench_for_zephyr.html) and [Ac6's listing](https://marketplace.visualstudio.com/items?itemName=Ac6.zephyr-workbench) explicitly cover SDK, build, flash, debug, Kconfig, Devicetree, memory, sysbuild, SPDX and AI/MCP. Confirmed overlap for Ac6; other competitor features still require hands-on checking.
2. **Tracing products exist.** [Zephyr tracing guide](https://docs.zephyrproject.org/latest/services/tracing/index.html) documents CTF, Percepio View and other backends. No second viewer should be built.
3. **2026 survey.** [Zephyr's own summary](https://www.zephyrproject.org/zephyr-at-10-a-decade-of-open-source-embedded-innovation/) reports maintenance 49%, safety certification 40%, learning curve 38%. This is a reported sample, not measured demand for our paid product.
4. **EU CRA.** [European Commission reporting guidance](https://digital-strategy.ec.europa.eu/en/policies/cra-reporting) confirms reporting began 11 September 2026; [implementation schedule](https://digital-strategy.ec.europa.eu/en/factpages/cyber-resilience-act-implementation) says general provisions apply from 11 December 2027. The tool supplies evidence, not legal advice.
5. **SPDX upstream is mature.** [west spdx documentation](https://docs.zephyrproject.org/latest/develop/west/zephyr-cmds.html) supports SPDX 2.3 and 3 Build Profile with file/source/tool relations; CONFIG_BUILD_OUTPUT_META and per-image sysbuild handling matter. [Zephyr 4.5 announcement](https://www.zephyrproject.org/zephyr-rtos-4-5-now-available-two-new-architectures-multimedia-pipelines-sboms-and-more/) dated 2026-10-28 confirms fresh support. Reuse; do not clone SBOM production.
6. **Upgrade novelty not verified.** Prose migration guides and board-model changes are evidenced by the proposal; a thorough competing-tool search is still needed before claiming unique automated impact analysis.
7. **Generic MCP novelty disproved.** Ac6 explicitly lists AI-agent access to build/configuration diagnostics. Narrow the proposed gap to offline cross-tool provenance, compiled/config-enabled applicability and Zephyr-version impact, still subject to controlled tests.

[Nordic west ncs-sbom](https://nrfconnectdocs.nordicsemi.com/ncs/latest/nrf/scripts/west_commands/sbom/README.html) walks Ninja dependencies and validates archive inputs. Any compiled-source attribution must integrate this existing capability or prove its own added value.

Unverified: Eclipse maintenance status, ST VS Code product direction, precise Zephyr IDE/nRF/STM32 feature parity, competitor fault-core/MCP hands-on tests. No superiority claim.
