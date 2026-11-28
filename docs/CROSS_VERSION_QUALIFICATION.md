<!-- SPDX-License-Identifier: Apache-2.0 -->
# Cross-version firmware qualification (issue #128)

Run the manual compile-only runner on a compatible host with two trusted,
self-contained, **ready** Zephyr 4.4.x and 4.5.x setups. The runner does not
bootstrap missing dependencies, install vendor blobs, or make a source-only
candidate ready. The controller needs the Workbench Python helper dependencies
(including PyYAML). Select the exact qualified board identifiers supported by both
releases; use a trusted application with no external source dependencies.

```text
python scripts/qualify_cross_version.py --original-setup C:/Zephyr/4.4 --candidate-setup C:/Zephyr/4.5 --application C:/Projects/hello --output C:/Evidence/trial-01 --board qemu_x86/atom
```

The default is read-only plan validation. Add `--confirm-build` only after
reviewing both installations and application sources. Repeat `--board` for
each cross-vendor target. On Linux use the corresponding absolute paths to
separately provisioned self-contained environments; the Windows managed wizard
does not provide Linux provisioning. Do not share an installation root.

Each board gets two copies of the fingerprinted application, two independent
pristine West builds using selected tool paths, logs, and a JSON evidence
report with before/after input checks and ELF, Kconfig and Devicetree hashes.
No original application, lock or binding is modified. A failed build records
incomplete evidence; an interrupted process may leave only logs. Do not reuse
the evidence path. A compile pass does not imply matching binaries, semantic
configuration equivalence, physical execution, timing or successful flash/debug.
Git transport is local-only, but arbitrary trusted build scripts are not
OS-sandboxed; conduct offline qualification on a network-disabled test host.

Physical checks remain explicit manual work on dedicated hardware: establish
board/probe and firmware identity, program only with approval, test reset,
breakpoints, peripherals and tracing as applicable, preserve console logs and
measured outcomes. Repeat on Windows and Linux, including archive recovery,
network interruption and mismatched metadata. No physical-device or production
qualification is inferred from fixture tests or a compile matrix. G0 remains open.
