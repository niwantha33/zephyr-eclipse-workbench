# Installer hotfix 0.1.7 — 2026-10-24

The user's Windows 0.1.6 setup successfully verified portable Python, Git and
all package downloads, then installed pip/setuptools/wheel. It failed while
generating Docopt 0.6.2 metadata: setup.py imported `docopt` from its own source
directory, which the embedded runtime's `._pth` isolation excludes.

The installer now builds this reviewed source distribution in a separate
`python -I` process. A small wrapper adds only its verified staging directory to
that process's import path and runs setup.py to create a wheel. No global
`._pth`, Python environment-variable or registry change is made. The wheel's
package identity, ZIP integrity and retained licence are verified; its hash and
source hash are recorded in `cache/docopt-local-build.json`. The final install
uses this wheel with `--require-hashes` rather than asking pip to build the sdist.

Source archives are checked again before execution. Unreviewed sources, links,
traversal paths, oversized archives and unexpected package identities are blocked.
All upstream artifact versions and download hashes remain unchanged. Retrying
the same managed folder reuses the user's verified Python, Git and package caches.

Validation:

- 32 Python tests passed, including a real isolated-process fixture that fails
  with the old import path and builds successfully with the scoped source path.
- Actual pinned Docopt source: the original `ModuleNotFoundError` was reproduced
  with `python -I` in a fresh Linux interpreter after the pinned bootstrap tools
  were installed. The fixed build produced and installed its wheel; pip check
  passed. Cache paths contained spaces and Unicode.
- Java checks: 31 process/command, 27 update and 11 bootstrap checks passed when
  run directly. The descendant-termination assertion failed on two initial
  full-script runs in this container, then passed in a direct cancellation probe
  and direct CoreTests run. No production process-handling code was changed.
- The build cache contained three invalid zero-byte Eclipse dependency JARs;
  they were repaired using the matching files from a checksum-verified official
  Eclipse 4.41 distribution before rebuilding.

Total distinct automated Java/Python checks: 101. Maven/Tycho's five modules
built successfully; package validation passed and its installer helper exactly
matches the tested source. Package version: 0.1.7.202610240835.
ZIP SHA-256: `2ba2574165cd4b7302b57a72d978d175e33c134b207dfd95318cd9c0b4f229cc`.
Headless Eclipse 4.41 provisioning replaced 0.1.6.202610232303 with
0.1.7.202610240835; the new installed feature root was present and the old root
was absent after the upgrade.
This is still a development
preview: the complete native Windows installer and later SDK/build phases need
Windows validation. The Linux isolated-process reproduction targets the specific
reported failure; it is not a substitute for that end-to-end test.

Reference: [CPython module search-path isolation](https://docs.python.org/3.13/library/sys_path_init.html#pth-files).
