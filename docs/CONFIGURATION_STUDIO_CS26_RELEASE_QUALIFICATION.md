<!-- SPDX-License-Identifier: Apache-2.0 -->
# Configuration Studio CS-26 — release-candidate qualification

**Release branch:** `feat/configuration-studio-cs23-visual-kconfig`, PR #272.
**Foundation:** merged PR #271 (CS-20–CS-22).
**Status:** implementation and automated gate wiring delivered; **release not approved until exact PR-head checks pass and real installed UI is reviewed**.

## Scope and release evidence

| Work package | Implemented contract | Automated evidence | Acceptance limitation |
| --- | --- | --- | --- |
| CS-23 | Graphical on/off request, explicit override removal, requested vs effective, two-step review | Java request-gate tests, source-backup atomic writer tests, installed UI smoke | Full in-browser file diff and arbitrary typed request form deliberately deferred; advanced editor owns these |
| CS-24 | Exact selected board/profile upstream Kconfiglib metadata: type, prompt, visibility, assignable values, direct dependencies, expression result, definitions, select and imply declarations | Pure Java JSON/injection/provenance tests, bundled-script check, installed bridge denial | No invented transitive-closure graph, no claim all reverse selectors found |
| CS-25 | One configuration view and existing Hardware Studio, exact generated-DTS controller deep-link, source browser and selection return via window-scoped identity hub | Pure Java bounded focus serializer and Eclipse bridge tests | Physical package pad, pinmux, live registers and active runtime still unverified |
| CS-26 | Fail-closed CI contract: bounded pinned-Tycho retry, full core tests, p2 artifact inspection, native Eclipse smoke in 1920×1080 and 1366×768, Windows managed firmware and CodeQL | `scripts/test_core.py`, `scripts/verify_site.py`, `scripts/editor_smoke.py`, `tests/tools/test_configuration_studio_release.py`, workflow gate | Mixed-DPI Windows hardware screenshot, installer upgrade/rollback, board-on-desk and UI approval still require humans |

## Build failure RCA — 20 November 2026

PR #272's first observed run had **passing** Windows managed firmware, three Python matrix jobs, and CodeQL. The Linux package job failed when Maven Central returned unavailable for pinned `org.eclipse.tycho:tycho-maven-plugin:5.0.4`; the strict evidence gate consequently failed as designed. This is *not* a passing qualification and does not prove Eclipse packaging failed to compile.

The repository already ships `scripts/retry_maven_tycho.py` and corresponding unit tests (originally added after an earlier Tycho resolution incident). CS-26 uses that tested script from the PR packaging job, with exactly three attempts, a fixed qualifier, `-U`, clean/retry and strict refusal to retry unrelated compile/test/p2 errors. We do not switch Tycho versions, Maven repos or suppress failures. If the Tycho artefact remains inaccessible, the release remains blocked.

## Required exact-commit release gates

1. **Pure local gate:** `python scripts/test_core.py` (Java --release 21, source guards, request parser, selected-build inspection, DOM asset checks and pre-existing regression suite).
2. **Linux packaging:** clean Tycho full `verify` and `python scripts/verify_site.py`, including both offline studio HTML assets, compiled request gate and core features.
3. **Real installed UI:** `scripts/editor_smoke.py --native-workbench` against the built p2 site under xvfb at 1920×1080 and 1366×768; invalid browser actions rejected; source-edit review remains native; screenshots retained.
4. **Native Windows managed firmware:** all existing seven-target observations and the matrix's code-specific test suites, no skipped or fabricated target results.
5. **Python and security:** Python 3.9/3.11/3.13 CLI tests, CodeQL Java and full Evidence qualification gate.
6. **Human qualification (not automated):** detach/dock view, test at 100/125/150/200% scaling, check high-contrast/light/dark readability, build exact TI/STM32/ESP targets, confirm `prj.conf` backup and source reloading after restart, test installed update and rollback, measure hardware behavior only on real board.

**Do not merge** unless required CI is green. Native screenshot absence or a SWT Browser fallback must not be presented as visual acceptance. Hardware qualification is deferred, not marked passed.

## User acceptance (read-only first)

- Select a Zephyr project and configured board/profile. Open Zephyr → Configuration Studio; verify the displayed receipt.
- Hardware → choose an observed controller → **Open exact controller in Hardware Studio**; inspect DTS/DTSI sources; selecting it in Hardware Studio should maintain the exact context when returning.
- Kconfig → choose `CONFIG_GPIO` or another actual symbol → inspect Kconfig type, direct dependencies, source definition and select/imply expressions. Missing Kconfiglib must show unavailable rather than inventing a dependency.
- Enable/Disable → preview the project request → continue to native review → either cancel (no write) or explicitly apply and verify timestamped backup. A requested change must remain distinct from effective build value until a new successful build.
- Change selected board/profile, edit source in another Eclipse window, delete a source, then try the old action; verify fail-closed rejection and no overwrites.

Nothing in this release claims actual peripheral pin assignment or runtime driver enablement.
