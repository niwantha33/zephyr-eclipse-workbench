# First development build report

Date: 2026-10-23. Stage: pre-v0.1 development slice, not a stable release.

## Implemented

- Machine-local preference page for Python, West workspace, application and exact board target.
- Environment diagnostics that ask West for the actual Zephyr source location.
- Board-name discovery and explicit qualified-target selection.
- Incremental and pristine firmware build commands through Python -m west.
- Per-board build output directory, streaming console, nonzero-exit reporting, timeouts and cancellation with descendant cleanup.
- Two compiled bundles, an Eclipse feature and local p2 repository ZIP.
- Manual GitHub Actions packaging workflow; no public hosting or automatic workflow execution.

## Build environment

| Component | Value |
|---|---|
| Test host | Linux x86_64 |
| Java | Ubuntu OpenJDK 21.0.12+8-1~24.04 |
| Maven | Apache Maven 3.9.11 |
| Tycho | 5.0.3 |
| Eclipse target | 4.41, immutable R-4.41-202608281142 repository |
| Resolved ECJ | 3.45.0.v20260224-0835 |
| Packaged bundle/feature version | 0.1.0.202610231613 |
| Declared build environments | Windows x86_64 and Linux GTK x86_64; declaration is not runtime validation |

The local network required proxy settings for Maven/p2 and a locally extracted JDK. Those machine-specific settings and downloaded tools are not committed.

## Results

| Check | Result | Scope |
|---|---|---|
| scripts/test_core.py | PASS: 29 checks | Compiled with javac --release 21 and executed real subprocess fixtures |
| Maven clean verify | PASS: exit code 0 | Core/UI compilation, feature and p2 repository packaging |
| scripts/verify_site.py | PASS | ZIP/JAR integrity, compiled classes, included licence text, command/preference classes, feature references, p2 metadata |
| GUI installation and interaction | NOT RUN | Requires an Eclipse desktop installation |
| Windows runtime | NOT RUN | Linux compilation does not prove Windows behaviour |
| Actual Zephyr firmware build through plug-in | NOT RUN | Existing user-reported terminal build is external baseline evidence only |
| Flash, serial, debug and peripheral tests | NOT RUN | These features are not implemented |
| Automatic updates/recovery | NOT RUN | Requirements only |
| Complete dependency licence audit/SBOM | PENDING | No upstream SDK/runtime libraries bundled; not a compliance certification |
| GitHub Actions workflow | NOT RUN | Manual trigger only |

The process tests cover literal arguments, Unicode/spaced paths, missing workspace/application, board output isolation, nonzero exits, large merged output, bounded capture, timeout, cancellation, output-handler failure and owned child termination. They do not establish actual West, SDK or hardware compatibility.

## Development package

Build output: releng/repository/target/workbench-repository-0.1.0-SNAPSHOT.zip

Size: 45,496 bytes. SHA-256:

```text
26a084a813cb5c1aab99ef83480fae756219f5a29e5db93a0851d3f4913c9a00
```

The site contains this project's two plug-in JARs, one feature JAR and p2 metadata. Future rebuilds use a different build qualifier and therefore may have a different checksum. Development artefacts are unsigned and remain private.

Follow DEVELOPMENT.md for installation. The next acceptance check is a real Eclipse 2026-09 installation and use against the user's existing workspace, before claiming the complete v0.1 milestone.
