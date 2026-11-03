<!-- SPDX-License-Identifier: Apache-2.0 -->
# Project Reproducibility and Zephyr Version Management

Status: **development slice; not a released or qualified upgrade assistant**.
See Evidence Engine Program v2: R2.6 (environment lock) and R3 (upgrade
impact analysis). Gate G0 is still open.

## Principle

A Zephyr firmware project must be tied to **its own immutable source and
toolchain identity**, not to whichever mutable West setup happens to be
selected in global Eclipse preferences. Never equate a Zephyr tag alone with
a fully reproducible firmware environment.

Zephyr's official `west manifest --freeze` resolves imported manifests and
pins each project to a commit SHA. The manifest repository (Zephyr itself for
the normal setup) is additionally recorded by its exact checked-out SHA.
The workbench does not parse or reinterpret upstream manifests itself.

## Existing implementation in this PR

After a successful native Eclipse firmware build, the builder attempts to
create these files once, automatically:

- `.zew/west-frozen.yml`: verbatim native West frozen manifest.
- `.zew/project-lock.json`: schema 1; board target; Zephyr version and SHA;
  frozen-manifest SHA-256; name/path/URL/SHA of each module; SDK version;
  selected Python, Git, CMake and Ninja executable SHA-256 and versions;
  West version; application file fingerprints.

The lock does **not** store absolute setup folders, so the application can
be cloned onto another PC and reattached to a compatible setup. It is safe
to commit both files to the application's own source repository. The
application itself must also be committed or archived: source hashes are
*not* copies of those files.

The helper first uses native West `manifest --freeze`. For a sparse
managed setup intentionally omitting unused modules, it can fall back to
native `manifest --resolve` **only when every resolved revision is already
an exact 40-character SHA**. It records absent modules explicitly in
`modules_not_installed` and verifies every installed module's SHA and clean
checkout. A floating branch/tag, altered installed module or unresolvable
import cannot create a project lock. No unrelated modules or proprietary
blobs are fetched automatically, and failures do not invalidate an otherwise
successful firmware build. An absent module's *source identity* is pinned,
but its bytes were not verified locally. This is not an archival guarantee.

The lock is never overwritten by a subsequent build. On each subsequent
native build with a lock, the workbench checks the pinned environment
before configuring firmware. Any Zephyr/module/SDK/host-tool drift stops
that build, with a console report. Ordinary application source edits do
**not** invalidate this build-time environment gate. Manual full Verify
also reports changes to the application files.

Available Zephyr menu items:

1. **Snapshot Project Environment...** — explicitly retry or inspect
   lock creation after preparing an appropriate complete West setup.
2. **Verify Project Environment...** — compare today's complete environment
   and application file hashes to the saved snapshot.
3. **Preview Upgrade Using Another Setup...** — select an already installed,
   separately managed candidate setup (for example Zephyr 4.5 alongside 4.4).
   The candidate must have an initialized West workspace, a completed
   environment, and its own toolchain. Read the console for per-module and
   tool differences. No source checkout is changed.

## Command-line backend for the Eclipse tools bundle

The Python helper `tools/project_versions.py` has the actions
`snapshot`, `verify`, `verify-env` and `plan`. For example, after
choosing explicit executables and an existing application:

```text
python project_versions.py snapshot --application APP --workspace WEST_ROOT \
  --python PYTHON --git GIT --sdk SDK_ROOT --cmake CMAKE --ninja NINJA \
  --board BOARD_TARGET
```

For a preview add `plan --candidate-workspace NEW_WEST_ROOT
--candidate-sdk NEW_SDK_ROOT --candidate-python NEW_PYTHON
--candidate-git NEW_GIT --candidate-cmake NEW_CMAKE
--candidate-ninja NEW_NINJA`, keeping the other inputs the same.
It returns a structured JSON difference report. It neither clones nor
updates repositories, and does not change the application.

## Choosing a candidate setup in Eclipse

**Preview Upgrade Using Another Setup** is a read-only comparison, **not**
an installer, automatic updater or migration command. It requires a
completed, *separate* Workbench setup folder with a different West
workspace. In particular, the current setup and its parent/symlink aliases
are not valid candidate environments.

If the user selects the same setup, the UI reports that a second setup is
needed *before scheduling a background Job*. There is one informational
message, not a misleading "Problem Occurred" failure and a second modal
error. If no project lock exists, the UI first requests a snapshot. Errors
from background version checks produce one Eclipse Job error and keep
actionable details in the console.

The initial managed first-build setup wizard currently provisions its
reviewed **Zephyr 4.4.0** profile only. It does not install Zephyr 4.5 by
simply choosing another directory. New-version automated installation is
a separate missing feature tracked in issue #128. Until that feature
exists, a 4.5 candidate must be installed as a **new, separately validated
environment** by supported external Zephyr/West provisioning steps and
registered as a ready Workbench setup. Do **not** mutate the 4.4 workspace
or point the project at an arbitrary Git checkout.

## Intended complete upgrade flow — not yet implemented

A later, separately reviewed setup manager must:

1. Discover *stable/LTS releases* from verified official Zephyr metadata,
   not track moving branches and not hard-code the newest release.
2. Show release notes, migration-guide link, affected boards, estimated
   disk usage, source/module SHA changes, SDK/Python/CMake constraints.
3. Install **side by side** in a new workspace and Python environment;
   verify expected checksums and licences; never run `west update` against
   the 4.4 workspace as an "upgrade".
4. Create a separate application branch/copy; preserve old project lock
   and original 4.4 build and output evidence.
5. Run CMake/Kconfig/devicetree analysis, a pristine build, tests and
   target-dependent flash/debug acceptance. Report untested hardware
   separately.
6. Promote the trial only after explicit confirmation; preserve rollback
   with a real, still-available prior environment.

Do not implement network-triggered remote changes on Eclipse startup.
Release discovery may notify but cannot silently change a project.

## Five-year retention beyond Git SHA pinning

A commit SHA is an exact **identifier**, not a guarantee that its Git
objects will still be retrievable in 2031. A true archival feature must
add verified `git bundle` files for each needed source repository or
an approved immutable source mirror, plus SDK, Python wheel, host-tool and
vendor-blob archives where redistribution rights allow. Store checksums,
the provenance of each archive, tested host/architecture and a restore
procedure. If archives are absent, say **source identified, long-term
availability unproven**.

Similarly, tool executable fingerprints alone do not attest every SDK
header/library, and a successful rebuild is not proof of bit-identical
output. Build paths, compiler timestamps and signing keys can affect
artifact bytes. A later reproduction check should compare configured
inputs and firmware hash while reporting explainable differences.

## Validation and acceptance

Unit tests under `tests/tools/test_project_versions.py` mock West/Git and
cover frozen SHAs, unsafe manifest paths, dirty modules, missing modules,
immutable lock files, manifest tampering, toolchain drift and refusal to
upgrade a workspace in place.

Still required before production claims: live West 4.4 and 4.5 end-to-end
fixtures, native Windows and Linux clean-room tests, Eclipse build timings,
cancel/retry tests, a persisted-project relocation test, source archive
restore with upstream unavailable, and a physical-board migration test.
This PR does not pass the G0 release gate.
