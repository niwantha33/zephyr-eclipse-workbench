# Installer hotfix 0.1.9

## Failure and correction

A fresh Windows setup installed packages and passed `pip check`, then the
long-running installer failed at SDK extraction with `No module named 'py7zr'`.
The embedded runtime starts with an explicit site-packages search path before
that directory exists. An import search can cache that absent directory as
`None` in `sys.path_importer_cache`. Pip runs in a child process and creates the
directory, but its fresh import state does not refresh the parent's cache.

After package installation and `pip check`, invalidate the running installer's
import caches and import `py7zr` in that same process. Report successful extractor
verification before entering the SDK phase. A failed import stops installation.
Keep package versions/hashes, Python isolation, source preservation and existing
download verification unchanged.

Python documents cache invalidation for modules installed during execution:
<https://docs.python.org/3.13/library/importlib.html#importlib.invalidate_caches>.

## Validation

- Reproduced the missing-directory negative cache using a real child process
  creating a module after the parent had searched the absent package path.
  Import fails before refresh and succeeds through the new verification helper.
- Installed actual `py7zr==1.1.4` into an initially absent target directory using
  pip in a child. The parent reproduces `No module named 'py7zr'`, then imports
  successfully after invalidation. A real 7z write/extract round trip passes.
  This integration check uses Linux-compatible package dependencies, not the
  Windows binary wheels; those remain a native Windows validation requirement.
- 36 Python setup/inventory checks pass, including the new regression and a
  broken extractor import guard. 31 Java core, 27 update and 11 bootstrap checks
  pass (105 total).
- Maven/Tycho returns success and the resulting distributable passes p2,
  bundle, licence and byte-for-byte setup-helper verification.
- Headless Eclipse provisioning upgrades 0.1.8.202610240858 to
  0.1.9.202610241002, replacing the old root feature.
- Update-site ZIP SHA-256:
  `649a8119c5de97c5daa37c8d5803caf31ac9e49c74604bc703e52cad3f40c719`.

Native Windows end-to-end setup and hardware operation remain pending.

## Retry

Update using **Zephyr > Check for Workbench Updates**, restart Eclipse, and retry
**Set Up Zephyr** with the same failed destination. Verified downloads and packages
are reused. No existing installation needs uninstalling or deleting.
