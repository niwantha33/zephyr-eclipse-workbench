# Tools Manager — first release (0.1.4)

Open **Zephyr > Install / Manage Zephyr Tools** after updating and restarting.
This release delivers the detect/reuse slice and isolated Python-environment
creation. It does not download SDKs, West packages, host tools, source modules,
probe software or vendor blobs. Those installers remain the next slice and must
meet the dependency review rules in LICENSING.md.

## Reuse the existing Windows environment

1. Select workspace `C:\Zephyr_Workspace`.
2. Select Python `C:\Zephyr_Workspace\.venv\Scripts\python.exe`. Browsing to a
   workspace automatically proposes its `.venv` interpreter if present.
3. Leave SDK and host executable fields blank for discovery, or browse to the
   exact installations you want to use. An explicit invalid selection is reported
   and never silently replaced with a PATH candidate during detection.
4. Click **Detect / Refresh**. The background job shows phases, an indeterminate
   progress indicator, detailed table rows and a log. Cancel Operation interrupts
   running child processes; closing the manager also cancels the job.
5. Review Component, Requirement, Installed version, Status, Location and Action.
   The table scrolls horizontally for long paths/details. SDK compiler entries
   include Xtensa, Arm, RISC-V and other installed architecture toolchains.
6. Click **Use Detected Tools** to bind the environment. Python, venv, workspace,
   West, CMake, Ninja and Git must pass before saving. Missing or unverified
   packages, SDKs, modules or optional utilities remain visible; this button
   does not assert that the entire environment can build every board.

Selected paths stay in Eclipse workspace preferences. Build, board discovery
and environment commands inherit the chosen SDK and explicit tool directories.
The selected directories precede the virtual environment and existing PATH.
If changing a compiler/build tool for an already configured build directory,
use Pristine Rebuild so CMake does not retain a previously cached tool path.

## Create an isolated Python environment

Use an installed base Python executable and **Create Isolated Python Environment**.
Select a parent directory; the manager chooses a new `zew-venv-<random>` child and
shows its exact path before creation. Python's standard `venv` module installs
its bundled pip without downloading packages. Existing environments are preserved.

The manager verifies that the resulting interpreter is isolated, then selects it
in the form. Click Detect / Refresh afterwards. West and Zephyr packages are not
installed by venv creation, so a new empty environment is not yet ready for use.
Failed/cancelled creation leaves a `.zew-incomplete` marker in the new directory;
the inventory reports Incomplete and does not allow that environment to be bound.
No automatic deletion is performed on an interrupted environment.

## What each check establishes

| Component | Evidence and limitation |
|---|---|
| Python / venv | Selected interpreter version, isolated prefix, incomplete marker |
| West | Installed package version against checkout minimum when available |
| Workspace / source | `.west/config`, West metadata (or labelled conventional fallback), VERSION |
| Modules | Manifest directory presence only; revisions, integrity and blobs not audited |
| CMake | Selected executable version against checkout CMake minimum |
| Ninja / Git | Baseline minimum 1.10 / 2.25 and a successful version command |
| SDK | Explicit path, environment, standard locations or Windows CMake registry; preference for checkout SDK_VERSION; actual build validates compatibility |
| Architecture compilers | Recognized legacy and SDK 1.x GNU layouts, executable presence and successful GCC version command |
| DTC | Optional system/SDK host-tool discovery and version check; requirement depends on operation |
| Python packages | Local base requirements, includes, host markers and installed versions; no resolution/download; optional feature requirements need separate review |

Ready means that row's stated check passed. It does not establish package licence
eligibility, full SDK compatibility, every optional Zephyr feature, flash/debug
capability or firmware execution. The first release does not install additional
packages, initialise West or run `west update` or `west blobs fetch`.

## Follow-up installer slice

Add reviewed exact-version package manifests, licence/provenance records,
checksum verification, architecture-specific SDK downloads, staged package
repair, retry/resume, offline inputs, space checks and verified completion. Keep
firmware projects and working environments pinned throughout that process.
