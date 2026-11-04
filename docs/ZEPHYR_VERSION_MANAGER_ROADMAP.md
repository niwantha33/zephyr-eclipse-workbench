<!-- SPDX-License-Identifier: Apache-2.0 -->
# Zephyr Version Manager — staged implementation and acceptance

**Status (2026-11-04): development only. Not a released or build-ready upgrade assistant.**
Goal: rebuild an archived project in five years and migrate 4.4 → 4.5 safely.

## Architecture

There are four independent version identities:

1. **Workbench IDE** — Eclipse p2 channel.
2. **Zephyr source** — tag, manifest Git SHA, all West module SHAs, source URLs, archive availability.
3. **Host build environment** — Python/West/wheel hashes, CMake, Ninja, SDK/toolchain and vendor tools.
4. **Application** — versioned source, board and SoC qualifiers, overlays, Kconfig, build settings, ELF provenance.

The source checkout is not a firmware-ready environment without a compatible SDK
and verified build. A commit SHA identifies source but is not an offline archive.

## Milestones: one reviewable PR per stage

| Stage | Feature | Acceptance |
|---|---|---|
| 0 | Project lock and drift guard (existing code) | Real 4.4 build, exact frozen commits, reject environment drift |
| 1 | Separate official release source checkout (this PR) | No original writes, exact tag and module SHAs, Windows/Linux regression tests |
| 2 | Per-release SDK and tool manager | Official compatibility, approved checksums and licences; isolated 4.5 and 4.4 SDKs |
| 3 | Independent application migration and trial | Real pristine 4.4 and 4.5 builds; Kconfig/DTS/board/compile diagnostic diffs |
| 4 | Promotion and rollback | Explicit confirmation; original 4.4 environment remains working after failure |
| 5 | Five-year archival restore | Source bundles, licensed SDK/wheels, tool digests, offline clean-host restoration |
| 6 | Release qualification | Real Windows and Linux Eclipse installed UI, seven-vendor matrix and physical target evidence |

Stages 2–6 are not provided in this PR. Follow-up issue #128 tracks the complete outcome.

## Stage 1: what it does

Zephyr menu: **Prepare Separate Zephyr Release Sources (Stage 1)**.
Select a stable exact tag (example v4.5.0) and a *new* folder. Confirm
the network download explicitly. It uses the existing managed
Python/West/Git to run upstream commands, without editing the user's
original environment, application, Eclipse binding or SDK.

The backend tools/release_sources.py offers:

- plan — offline, read-only, validate release and destination.
- install --allow-network — stage in a fresh folder, invoke official
  West init with the selected tag and West update. Verify Git tag commit,
  Zephyr VERSION and all modules through official West manifest --freeze.
  Store .zew/source-lock.json and .zew/source-frozen.yml.
- compare — check source evidence and report Zephyr/module SHA changes
  against the original application's project lock. No build is implied.

A successful new source tree has a .zew-source-ready marker, **not**
.zew-ready. The existing managed ProjectContext therefore rejects it as
a build-ready environment. No SDK or Python compatibility is claimed.

A cancelled or failed operation leaves staging for inspection and never
removes or overwrites a user workspace. A destination inside, identical
to, or containing the original workspace is rejected. Source Git access
is HTTPS, without a claim of signed-tag verification or five-year availability.

## Stage 2 design decision

Current board_dependencies.py hard-codes Zephyr 4.4 commit
684c9e8f32e4373a21098559f748f06915f950c9 and SDK 1.0.1.
The existing bootstrap setup.py similarly pins 4.4. Replacing the
global constants with 4.5 would break existing projects.

Instead, add per-environment, versioned and checksum-reviewed provisioning
profiles. A new 4.5 profile must resolve official SDK/west/compiler requirements
and board-specific dependencies independently, with no downgrade of a
4.4 environment. Do not bind application builds until the candidate toolchain
has passed a real cross-vendor firmware matrix.

## Stages 3–5 acceptance

Stage 3 creates a separate application copy/branch. Generate new
project-lock and frozen West manifest for the candidate, run actual
CMake/Kconfig/Devicetree configuration, a pristine firmware build and
appropriate tests, and compare concrete produced artifacts. Unsupported
boards produce an explicit unsupported result rather than an invented fix.

Stage 4 has no automatic promotion. Users inspect changes and approve
switching the project setup; the previous setup is retained for rollback.

Stage 5 exports verified Git bundles and source files, plus legally
redistributable SDK and wheels with hashes and licences. Prove restoration
using an offline clean machine, report byte differences in firmware
without claiming universal deterministic output.

## Trust and evidence

Stage 1 tests use mocks and local fixtures. CI compile/package success
does **not** prove a real Zephyr 4.5 install or a compatible firmware build.
Before acceptance, perform a genuine v4.5.0 West init/update/freeze on
Windows and Linux, inspect hashes and cancellation. West/Git execute on
the host with user privileges; explicit user approval is mandatory.

The Evidence Engine Program's G0 and G3 gates remain open. Do not
merge or publish a stable Workbench release based solely on these fixtures.
