<!-- SPDX-License-Identifier: Apache-2.0 -->
# Release stabilisation board — 2026-11-10

**Scope:** stabilise the existing Eclipse RCP/CDT Workbench, not the experimental
Theia/cloud architecture. **Disposition: NOT RELEASE-APPROVED.** Evidence Engine
G0 remains OPEN; a successful development p2 update is not a production approval.

## Verified repository baseline

- Latest GitHub installation release: [v0.1.21-preview.1](https://github.com/niwantha33/zephyr-eclipse-workbench/releases/tag/v0.1.21-preview.1), published 2026-10-28.
- At audit time, main was `aa5b6538dacc3caa1d889e6a7f43785e31dfce18` (README PR #219 merge, 2026-11-10).
- PR #219 [build run 42774514248](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/42774514248) passed Linux package/installed UI, Python 3.9/3.11/3.13, native seven-scenario Windows matrix and aggregate qualification on PR SHA `9c9ddd4dc299278e55413e45091dbd0713eecfa1`. **This does not verify the merged main SHA.**
- Last inspected successful main publisher was [run 42708309994](https://github.com/niwantha33/zephyr-eclipse-workbench/actions/runs/42708309994), for older main SHA `19fa67c1dd01df5cd142c8e9ca7220af6ec7c70e`.
- The `updates` branch channel advertised `0.1.21.20261109220433` at audit time, newer than the GitHub preview ZIP. It must not be equated to a newly published GitHub release.
- Open issues: [#128](https://github.com/niwantha33/zephyr-eclipse-workbench/issues/128) (side-by-side version/5-year archive).
  Open PR: [#205](https://github.com/niwantha33/zephyr-eclipse-workbench/pull/205) (draft cloud architecture).

## Release blockers: ordered execution

| Priority | Work item | Acceptance evidence | Current disposition |
| --- | --- | --- | --- |
| P0 | Exact-commit publication safety | Publisher must verify Linux installed Eclipse and seven real Windows firmware scenarios at the **same source SHA** before moving `updates`; failure leaves channel unchanged | Fix proposed in this PR; remote CI pending |
| P0 | Reproduce clean Windows installation | Download real `_p2.zip`, verify hash, install into supported Eclipse/CDT on a clean Windows x64 host, restart, run setup/import/build | **Not verified** on current candidate |
| P0 | Build/flash/debug end-to-end on owned physical hardware | Named board, probe firmware/tool versions, exact target, flashed ELF hash, breakpoint/step/register and safe Stop/relaunch; capture failures, not just passes | **Not verified** on current candidate |
| P0 | Release asset and update recovery | Verify release archive contents, independent checksum, first install, old-to-new p2 update, interrupted update and rollback; record immutable exact-source provenance | Archive CI partial; manual recovery not verified |
| P0 | Licensing and truthful distribution | Review exact p2 bundled units/notices and separately downloaded vendor tools, no unlicensed SVD/binary redistribution | [Licence audit](LICENSE_AUDIT.md) BLOCKED |
| P0 | Enforce repository qualification before merging | Require `Evidence qualification gate` and review on main ruleset; demonstrate failing PR is blocked | [Branch protection](BRANCH_PROTECTION.md) unverified |
| P1 | Native user interface review | Inspect actual packaged first-run, selected project/board/profile, YAML/DTS graph, SVD/memory views, Stop, high DPI and dark mode; retain unedited screenshots | CI smoke partial; manual visual review pending |
| P1 | RTOS and offline evidence qualification | Real saved CTF, buffer-loss accounting, CPU/thread timeline, fault log + ELF identity; unsupported states visibly unknown | Fixture evidence only for many paths |
| P2 | Release-specific feature polish | Smart binding, memory browser, source navigation and UX issues with reproducible regression tests | Triage after P0 |
| Deferred | Cloud/Theia contracts and full five-year restore | Separate release train with isolated gates; do not block the **scoped Eclipse development preview** | PR #205 / issue #128 remain separate |

**P0 completion rule:** all P0 entries must have reproducible evidence on the
chosen release candidate's exact commit. No exceptions inferred from an older
passing CI run or a marketing feature catalogue.

## Validation order

1. Merge the publisher safety fix only after the PR's exact-head qualification passes.
2. Record the successful **main commit** Windows, Linux, package, archive and
   qualification evidence. Keep the last known-good update channel if any job fails.
3. Install the exact candidate on clean Windows x64 Eclipse Embedded C/C++ and
   a supported Linux GTK desktop; test installation, upgrade and rollback.
4. Exercise `lp_mspm0g3507/mspm0g3507` with TI XDS110/OpenOCD, and
   `esp32s3_devkitc/esp32s3/procpu` with Espressif USB-JTAG/OpenOCD where
   physical hardware is available. Record exact board/probe/OS/West/Zephyr/SDK/
   OpenOCD/GDB identities. Do not fabricate absent hardware results.
5. Review independent SHA-256 checksums, SPDX/licence notices, exclusions,
   known limitations, actual release ZIP and notes. Review the branch ruleset.
6. Owner explicitly approves an appropriately labelled **development-preview**
   release only when its declared scope passes. Keep G0 OPEN until its separate
   Evidence Engine criteria are met.

## Release freeze policy

**No feature merges into the release candidate unless they fix a reproducible
P0/P1 regression and include a failing-before/passing-after test.** Do not merge
draft PR #205 into the Eclipse preview solely to clear the PR queue. Do not
close issue #128 as solved without the actual migration/archive evidence.

A successful CI build is `BUILT`, not `FLASHED` or `DEBUGGED`. A connected
view is not proof of readable physical target memory. Release notes must give
supported host/board/probe evidence and explicitly mark unsupported workflows.
