# Beginner setup — development preview 0.1.7

The new **Zephyr > Set Up Zephyr** wizard bootstraps a managed environment on
native Windows x64. No installed Python, Git, winget, terminal commands or global
PATH edits are needed. A welcome prompt offers setup in new Eclipse workspaces;
existing configurations are not prompted or replaced.

## First-user flow

1. Install/update the workbench in Eclipse and restart.
2. Choose **Set Up Zephyr** from the welcome prompt or Zephyr menu.
3. Choose an empty installation folder. The default is a new folder under your
   user profile. Retrying a previous managed folder is also supported.
4. Review the exact versions, destination, space estimate and licence records.
   Accept the installation plan, then click **Finish** to install and build.
5. Watch the wizard progress and **Zephyr Beginner Setup** console. Downloads show
   byte counts; package, SDK, source and build phases are labelled.
6. On success, **HelloZephyr** appears in Project Explorer and `src/main.c` opens.
   Use **Project > Build Project** to rebuild. If Eclipse Build Automatically is
   enabled, changes in source/configuration also trigger the project builder.

This first profile targets **qemu_x86/atom** and verifies compilation without
physical hardware. The wizard currently offers one pinned release/profile:
Zephyr 4.4.0, SDK 1.0.1 and the x86 GNU compiler. Emulator execution, generic
board/vendor selection, physical flashing and debugging are follow-up work.
It is not yet a complete all-board onboarding product.

## Isolation and recovery

- A workbench-owned portable Python 3.13.7 runtime and MinGit 2.50.1 are installed
  under the chosen directory. These are separate from existing user installations.
- All 32 Python artifacts are pinned by filename, version, size and SHA-256.
  They are downloaded from PyPI, then installed without network dependency
  resolution. Docopt is built locally from its pinned source distribution using
  pinned build tools; the resolver-only placeholder used during lock preparation
  is never included in the installer or lock.
  Release 0.1.7 builds the reviewed Docopt source in its own isolated process,
  explicitly adding only that verified source directory to the build import path.
  The generated wheel is checked for package identity, integrity and licence
  retention, then installed using its recorded SHA-256. Runtime `._pth` isolation
  remains unchanged. This fixes the Windows `No module named 'docopt'` failure.
- Python, MinGit and SDK archives are pinned as well. Licence/notice file paths
  and digests are recorded in `tools/setup-lock.json`; original notices remain in
  the downloaded distributions. Approval is for external build use, not tool-bundle
  redistribution or blanket certification of arbitrary firmware licences.
- Zephyr is checked out at immutable commit
  `684c9e8f32e4373a21098559f748f06915f950c9`. This first profile explicitly uses
  no external Zephyr modules and does not fetch vendor blobs or probe packages.
- Installations require at least 5 GiB free. Allow roughly 1 GB network transfer;
  exact source transfer varies. Complete downloads are reused after checksum
  verification; partial downloads restart. Python package/SDK downloads retry
  up to three times. A cancelled Git clone is staged separately and retried.
- A filesystem lock prevents two Eclipse instances installing into the same
  directory. Incomplete setup has no `.zew-ready` marker. Ready is written only
  after the expected firmware exists and local environment bindings are saved.
- Retry completed setup to reopen its project without reinstalling tools.
  Failed extraction may leave staging folders inside this managed installation;
  these never contain or replace an existing external user project.
- Existing starter source edits are preserved on retry. An unowned nonempty
  installation/project folder or edited managed Zephyr checkout causes a visible
  error. No user source is reset or overwritten.
- Environment paths remain in installation state and Eclipse workspace metadata.
  Portable project metadata stores the board/release profile. Native project
  builds use their own environment mapping. Existing global prototype preferences
  are retained if already configured.

## Validation boundary

Unit/process tests, the packaged Eclipse feature, real Windows archive extraction,
and a Linux cross-build of the generated starter have been checked. This is a
Windows installer preview: first-run dialogs, portable Python/pip execution,
native compiler execution and the full clean-Windows path still require Windows
testing. Internet/proxy permissions, antivirus locks and enterprise execution
policies can affect bootstrap installation; failures remain visible in the console.

The earlier Tools Manager remains the route for existing Zephyr environments
and additional boards. No existing firmware project is migrated automatically.

