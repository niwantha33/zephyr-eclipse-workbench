# Beginner installer preview — 0.1.6

Package: 0.1.6.202610232303, 2026-10-23. This is a Windows x64 development preview,
not a claim that the clean-Windows acceptance test has passed.

| Check | Evidence |
|---|---|
| Java command/process tests | 31 passed |
| Update transport/staging tests | 27 passed |
| Java bootstrap tests | 11 passed on Linux: hashes, cache corruption, HTTPS requirement, real ZIP extraction, cancellation, traversal/drive paths and existing symlink rejection |
| Python tools/setup tests | 28 passed: original inventory plus profile/review gates, caches, safe archive names, pinned requirements, source preservation, properties and success/failure readiness |
| Maven/Tycho packaging | All five modules built; installer classes, resources, package approvals and licence records verified |
| Source/package agreement | Packaged setup.py, setup-lock.json, setup-licences.txt and inventory.py exactly match source |
| Real Windows SDK artifacts | Both 1.0.1 archives verified against GitHub release digests, extracted with the shipped helper, expected GCC executable located; completed SDK reused on retry |
| Pinned wheel layout verification | Actual CMake and Ninja wheel records checked against installer paths, including Windows scripts relocation |
| Real package bootstrap | Pinned pip/setuptools/wheel installed, pinned Docopt source built/installed, pip check passed in a fresh Linux interpreter with spaces/Unicode in cache paths |
| Actual firmware build | Generated starter compiled against immutable Zephyr 4.4.0 and SDK 1.0.1 with GCC 14.3.0 on Linux; 135 build steps succeeded; ELF output verified |
| Eclipse upgrade | Headless p2 upgrade 0.1.4 to 0.1.6; installed feature root replaced |

Total: 97 automated Java/Python checks. The real firmware build used Linux
Python 3.12.14 and Linux variants of the pinned build tools; it does not validate
Windows CPython 3.13.7, DLL loading or native compiler execution.

ZIP SHA-256: `1703970cc82d2056409cefad4baa734fd475b38b74d8044462abf6cba0c15dfd`.

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

The 0.1.5 candidate was withdrawn from the channel after final inspection found
that Ninja 1.13.2 places its executable through wheel `.data/scripts`, rather than
inside `ninja/data/bin`. Release 0.1.6 records and uses the reviewed installed
paths, checks executable presence before proceeding, and adds a regression test.
Actual pinned CMake and Ninja wheel layouts were checked against those records.
The channel was kept on 0.1.4 while preparing the corrected package.
