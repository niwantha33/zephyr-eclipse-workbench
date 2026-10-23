# Beginner installer preview — 0.1.5

Package: 0.1.5.202610232258, 2026-10-23. This is a Windows x64 development preview,
not a claim that the clean-Windows acceptance test has passed.

| Check | Evidence |
|---|---|
| Java command/process tests | 31 passed |
| Update transport/staging tests | 27 passed |
| Java bootstrap tests | 11 passed on Linux: hashes, cache corruption, HTTPS requirement, real ZIP extraction, cancellation, traversal/drive paths and existing symlink rejection |
| Python tools/setup tests | 27 passed: original inventory plus profile/review gates, caches, safe archive names, pinned requirements, source preservation, properties and success/failure readiness |
| Maven/Tycho packaging | All five modules built; installer classes, resources, package approvals and licence records verified |
| Source/package agreement | Packaged setup.py, setup-lock.json, setup-licences.txt and inventory.py exactly match source |
| Real Windows SDK artifacts | Both 1.0.1 archives verified against GitHub release digests, extracted with the shipped helper, expected GCC executable located; completed SDK reused on retry |
| Actual firmware build | Generated starter compiled against immutable Zephyr 4.4.0 and SDK 1.0.1 with GCC 14.3.0 on Linux; 135 build steps succeeded; ELF output verified |
| Eclipse upgrade | Headless p2 upgrade 0.1.4 to 0.1.5; installed feature root replaced |

Total: 96 automated Java/Python checks. The real firmware build used Linux
Python 3.12.14 and Linux variants of the pinned build tools; it does not validate
Windows CPython 3.13.7, DLL loading or native compiler execution.

ZIP SHA-256: `3ab997a64b8eba78bc433b05abfeb4f409df9c09186eb328c23e90f94bba1e13`.

The first profile intentionally selects qemu_x86/atom, no external Zephyr
modules, and the x86 GNU compiler. No vendor blobs or proprietary probe packages
were fetched. Readable licence decisions and exact artifact/licence-file hashes
are shipped with the installer; approval covers external build use, not arbitrary
tool/firmware redistribution. All tool binaries are fetched from upstream rather
than bundled in the p2 update site.

Still unverified: a completely clean Windows install, first-run/wizard rendering,
portable Python pip/bootstrap execution, native SDK compiler DLL dependencies,
Windows path-length/antivirus/proxy policies, native Eclipse project builds and
automatic-build delta behaviour. Cancellation tests cover the process/download
primitives; desktop cancellation and cross-instance file locking need Windows
validation. Full CDT indexing, arbitrary vendor/board profiles, emulator execution,
physical flashing and debugging remain separate work.
