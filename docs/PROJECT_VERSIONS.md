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

## Recovery of a locked application (development slice)

Open **Zephyr > Project Environment Manager > Attach Original**. Select a
trusted, separately installed or restored setup folder containing
`.zew-ready` and `environment.properties`. The existing project lock is
verified using the candidate environment before its project-local binding
changes. The former binding need not be available. Project sources, the lock
and global preferences are not changed. A mismatch or cancellation leaves
the selected setup unattached. The next build of each target/profile
regenerates its cache before compiling; preserve original artifacts first.

This does not obtain missing source or tools. A schema-1 lock cannot attest
SDK contents, Python packages, externally installed host utilities, the OS
or bit-identical firmware. A matching record is not proof of an archival
restore. Use the existing CLI `verify-env` against the candidate to inspect
the same recorded identities. No tools from an untrusted setup should be
executed. Native integration and clean-machine recovery remain unqualified.

When the separately reviewed archive backend is installed, the same manager
also offers **Archive / Restore**. Export checks the lock, requests private
retention approval, and preserves the managed setup. Restore uses an explicitly
trusted current Python, verifies a separately retained archive SHA-256, restores
at the original path, and attaches only if the original project lock matches.
The CLI and restrictions are documented in `docs/ENVIRONMENT_ARCHIVE.md`.
Firmware comparison checks selected original/rebuilt files, not target identity.
These actions remain hidden until the backend is bundled.

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


## Version discovery and selection (development slice, 2026-11-24)

The **Zephyr > Help and Updates > Check Zephyr Versions...** entry opens the
existing separate-source manager. It now shows an offline inventory of the
selected West workspace, its selected SDK version, and recorded source-only
candidates. **Check Official Zephyr Releases** is an explicit network operation
which reads the upstream \`zephyrproject-rtos/zephyr\` GitHub releases API,
filters drafts/prereleases and displays the stable versions in numeric order.
The user can select a version and read the upstream release notes in Eclipse.
A separate **Check Workbench Updates** action continues to use Eclipse p2.

The chosen release drives the proposed new directory, but a manually edited
destination is never silently changed. The existing \`release_sources.py\`
plan/install/compare workflow remains unchanged: explicit confirmation, separate
staging, exact tag/manifest verification, source-only marker, no automatic
rebind, no overwrites, and no implicit \`west update\` to an installed workspace.

**Compatibility disclosure:** The current SDK is shown for comparison only.
The target release's \`SDK_VERSION\`, Python/West/wheel requirements, CMake,
Ninja, architecture-specific toolchains, HAL/board modules and vendor blobs
cannot be declared compatible from GitHub release metadata alone. These remain
unknown until a candidate checkout is inspected, a distinct environment is
provisioned and real firmware builds pass. A recorded source-only folder is
not considered build-ready or safe to select as the active project setup.

There is no background network polling for Zephyr releases, automatic SDK
installation, package upgrade, migration or project promotion in this slice.
The metadata check can fail offline or hit GitHub API limits; it must leave
the existing setup and previous version selection intact. Test with
\`python -m unittest tests.tools.test_release_catalog tests.tools.test_release_sources\`
(or \`python -m unittest discover -s tests/tools -p 'test_release_*.py'\`).
CI build/package and Windows Eclipse smoke testing are required before merge.


The **Inspect Selected Packages** button is another explicit, read-only
network request. It fetches a small allowlisted set of version-tagged upstream
files (SDK_VERSION, cmake/modules/python.cmake,
scripts/requirements-base.txt, cmake/modules/zephyr_default.cmake) and shows
side-by-side *current versus selected* SDK recommendation and minimum
Python/West/CMake requirements. It labels each comparison as a minimum check
or advisory, not as an installed or build-qualified candidate. For example,
the Zephyr 4.5.0 CMake build scripts require 3.28.0; the 4.4.0 and 4.5.0
SDK_VERSION files both currently read 1.0.1. This does not prove that their
modules, drivers or application ABI are compatible.

Metadata reads are bounded, timeout-limited and restricted to official upstream
hosts, exact stable tags and allowlisted paths. No permission to install,
execute downloaded code, change tools or modify manifests is implied.
