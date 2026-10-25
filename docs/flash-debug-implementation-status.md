<!-- SPDX-License-Identifier: Apache-2.0 -->
# Flash & Debug Implementation Status (0.1.22 foundation)

**Status: IN DEVELOPMENT — not a released Flash Engine, not Hardware Verified.**

This branch introduces the first vendor-neutral Flash/Debug Server foundation.
It deliberately leaves the release at 0.1.21: the user-facing 0.1.22
acceptance gate is **not met**.

## Implemented in this branch

- One pure Java `TargetOperations` planner shared by the Eclipse launch delegate
  and the project context-menu actions. No vendor-specific flashing scripts.
- Exact .zew-built-target receipt and generated `CONFIG_BOARD_TARGET` checks
  when Kconfig exposes that setting; all mandatory build outputs are checked.
- Flash and debugserver run against the already-built target with
  `west <operation> --build-dir <verified> --skip-rebuild`. No implicit build.
- An optional explicit runner name is syntax-checked by the planner, but a
  runner selector UI and compatibility discovery have **not** been added.
- A fail-closed in-process guard serializes physical runner actions within one
  Eclipse process, including Eclipse launch configurations. This is not an
  OS-wide or cross-IDE probe lock.
- A queued project action repeats validation after acquiring the guard.
- Existing QEMU Run and external Debug Server actions remain available.
- Offline fixture tests cover wrong CPU qualifiers, missing artifacts, invalid
  runner names, attempted option injection and generated target mismatch.

## Not implemented / release blockers

1. Machine-verifiable runner capability discovery and out-of-tree runners.
2. Backend-specific probe detection, selection and persistent verified identity.
3. Per-probe cross-process ownership and robust unplug/reconnect handling.
4. Programmer verification and independent firmware read-back verification.
5. Protected-region inspection and explicit destructive recovery workflows.
6. Native CDT DSF-GDB launch and source-level debugging (0.1.23).
7. Fault injection, serial-port lifecycle, debug-server ownership and multi-core
   debugging acceptance (0.1.24).
8. Physical hardware sessions and repeated 20-cycle measurements.

The existing Debug mode rejection is intentional until a correctly integrated
Eclipse DSF-GDB launch exists. **Start Debug Server** provides external GDB
server functionality only.

## Evidence matrix

| Acceptance item | Status | Evidence |
| --- | --- | --- |
| Core planner source compiles in Java 21 | LOCAL PARTIAL | Standalone test harness using stubbed surrounding core classes |
| Offline command planning checks | LOCAL PARTIAL | 13 checks passed using stubbed BuildArtifacts / WestCommands in sandbox |
| Repository's complete Java and Python tests | NOT RUN | Await GitHub CI / full checkout |
| Eclipse Tycho plug-in / p2 packaging | NOT RUN | No local Maven/Eclipse target platform |
| ST-LINK / STM32 hardware | NOT RUN | Physical device inaccessible |
| ESP32-S3 / ESP32-C3 hardware | NOT RUN | Physical device inaccessible |
| TI MSPM0 hardware | NOT RUN | Physical device inaccessible |
| Nordic / NXP hardware | NOT RUN | Physical device inaccessible |
| Breakpoints / register inspection / RTOS awareness | NOT IMPLEMENTED | Requires CDT DSF-GDB milestone |
| Programming/read-back verification | NOT RUN | Requires physical programmer and verified backend |

## Operational caveats

The planner validates artifacts and the recorded exact board target, but
a build receipt is not a cryptographic attestation or a full freshness
guarantee. Probe compatibility remains West/backend responsibility.
The command never passes an explicit `--erase` flag; nevertheless, some
backend defaults may erase flash regions as part of normal programming.
No independent memory-protection guarantee is claimed. Do not use this
preview for production or protected devices until the flash-region and
backend-policy gates are implemented.

## Next engineering steps

Use the pinned Zephyr runner APIs and generated configuration for capability
discovery, add verified probe selection and per-probe ownership, then build
the Eclipse CDT DSF-GDB debugger against the pinned 2026-09 Eclipse target.
Preserve the existing independent .zew-build paths, tool provisioning,
release automation and out-of-tree runner compatibility.

No changes to upstream Zephyr or end-user applications are required.
