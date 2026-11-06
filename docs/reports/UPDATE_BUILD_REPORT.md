# Private updater validation — 2026-10-23

## Version details release 0.1.3

Version 0.1.3.202610232212 adds About Zephyr Workbench with installed version,
build ID, Eclipse/Java versions, cached channel information, and Copy Details.
Notifications show installed and available versions. Console output identifies
the running plug-in. No token or secure-storage value is read by About.

All 56 existing checks passed; all five Maven/Tycho modules built successfully.
Package verification passed on the exact ZIP, including the compiled About
handler and menu registration. The verifier now extracts the versioned archive
into a fresh temporary directory to avoid stale exploded build output.
Headless Eclipse 4.41 provisioning upgraded 0.1.2 to 0.1.3 and verified replacement
of the installed feature root. Desktop rendering and clipboard behaviour remain
for Windows validation. The user reported completing the preceding update flow;
this is user-reported evidence, not an automated Windows test.

ZIP SHA-256: `2d26adaff81e0b9891ffd97c9efb935fb1691c5fea7dc9ac35e77c4e25a99076`.

## Initial updater release

Development packages: 0.1.1.202610232126 (bootstrap) and
0.1.2.202610232131 (first channel update). Source baseline: main
06c79fbfc0c5d0ba030e0bf4bf9682400c9c324d plus this implementation.

| Check | Evidence |
|---|---|
| Core command/process handling | 29 checks passed |
| Update manifest, HTTP and staging | 27 checks passed, including actual local HTTP transport, bounded bodies, no redirects, authentication errors, timeouts, cancellation and digest rejection |
| Java / build | OpenJDK 21.0.2, Maven 3.9.11, Tycho 5.0.3; five modules built successfully |
| Target | Immutable Eclipse 4.41 / 2026-09 target; Windows x64 and Linux x64 dependency resolution |
| Package verification | Both original bundles, feature, metadata, archive integrity and licence checks passed |
| Headless Eclipse install | Clean disposable Eclipse Platform 4.41: installed bootstrap 0.1.1 |
| Headless upgrade | Replaced feature root 0.1.1 with 0.1.2; new root present and old root absent |

Bootstrap ZIP SHA-256:
`e66565f4f28a2056eb9993775473f2c0195a6290018e6f3b00469919330c0c0d`.

0.1.2 ZIP SHA-256:
`d8bab165bdb93c27fb725857023d995214886ba7f4b5190c1ff19f61b7aac88d`.

The build cache needed repair after missing dependency artifacts; the succeeding
Maven build passed. Stale files remained in the exploded target directory, so the
0.1.2 archive was extracted into a fresh directory and that exact package was
verified and used for the successful upgrade test and update publication.

Still unverified: Windows secure-storage prompts, real user-token authentication,
startup notification rendering, the interactive p2 wizard/restart, installation
history recovery, hardware execution, and Eclipse 2026-12 M1 compatibility.
Headless p2 exercises provisioning without the desktop wizard. This remains an
unsigned development release. Guided tool installation and project wizards are
not implemented by this slice.
