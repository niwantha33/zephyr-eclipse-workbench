# Building the first development slice

Historical initial development notes. For installation and the current workflow,
start with the [fresh-install guide](FRESH_INSTALL.md). The current source has
features implemented after this initial slice; the current CI workflows and
target files are authoritative for build commands and dependency versions.

This source implements environment preferences, workspace diagnostics, board-name discovery with explicit qualified-target entry, incremental/pristine builds, a streaming Eclipse console and cancellation. It does not yet implement the full v0.1 milestone.

## Build prerequisites

- An open-source JDK 21 (JAVA_HOME points to the JDK).
- Apache Maven 3.9.11, with mvn on PATH.
- Python 3 for the dependency-free core test harness.
- Network access to Maven Central and the pinned Eclipse p2 repository on the first build.

The build uses Tycho 5.0.3 and the immutable Eclipse Platform 4.41 release repository in releng/workbench.target. This initial target does not include CDT APIs: C/C++ indexing integration is a later slice.

From the repository root:

```powershell
python scripts/test_core.py
mvn --batch-mode --no-transfer-progress verify
python scripts/verify_site.py
```

Alternatively run scripts/build.ps1 for core checks, packaging and site verification. It does not install Java/Maven or change system settings.

A manually triggered GitHub Actions workflow also packages the site and retains a private artifact for seven days. It does not run on pushes/PRs, publish a release, or enable a public update site. Its execution has not been verified in this first local build.

The output is a local p2 update site in releng/repository/target/repository and its generated ZIP alongside it. Only this project's feature/bundles are packaged; Eclipse, Zephyr, SDKs and vendor libraries are not bundled.

## Install locally

1. In Eclipse Embedded C/C++ 2026-09, select Help > Install New Software.
2. Click Add > Local and select releng/repository/target/repository from the built checkout. If using a ZIP, use Archive instead.
3. Select Zephyr Eclipse Workbench, review the installation details and finish.
4. Restart when requested. The Zephyr menu and Zephyr Workbench preference page should appear.

Development builds are not signed. They are local development artefacts, not a publicly hosted or audited stable release. Keep Eclipse's normal trust controls enabled.

## Use your existing Zephyr workspace

Open Window > Preferences > Zephyr Workbench and set:

| Setting | Example for the current learning environment |
|---|---|
| Virtual-environment Python | C:\Zephyr_Workspace\.venv\Scripts\python.exe |
| West workspace root | C:\Zephyr_Workspace |
| Application folder | C:\Zephyr_Workspace\external\zephyr\samples\hello_world |
| Exact board target | esp32s3_devkitc/esp32s3/procpu |

These values are examples only, not built-in defaults. Settings are local to the Eclipse workspace. This preview has one active application/board configuration; project-specific profiles follow later.

1. Zephyr > Check Environment locates Zephyr through West, including an external/zephyr layout.
2. Zephyr > Select Board lists names from the selected checkout; add required SoC/core/revision qualifiers in the second dialog. Automatic qualifier discovery is not implemented.
3. Zephyr > Build Application runs Python -m west build with an argument vector, not a shell string.
4. Zephyr > Pristine Rebuild clears only the selected managed build directory through West.
5. Zephyr > Cancel Operation cancels the active tool process and its owned descendants. Operations within this plug-in are serialized; another external West process is not locked by this preview.

Build outputs are under the application's .zew-build/<board-hash> directory. Incremental builds reuse that directory; different exact board targets use different directories. Ignore .zew-build in application Git repositories.

No commands fetch blobs, install SDKs, change pinned Zephyr revisions or flash hardware. A user-managed workspace can already contain proprietary components; this preview does not yet audit licence eligibility. A firmware build can therefore not be advertised as an audited open-source workflow solely because it succeeds.

## Developing the plug-in in Eclipse

Install Eclipse PDE if the chosen Eclipse package lacks it. Import the two projects under plugins using File > Import > General > Existing Projects into Workspace. Set a Java 21 execution environment and the target platform corresponding to releng/workbench.target. PDE source development may need the SDK/PDE features in the host IDE; the packaged end-user plug-in does not require PDE.

Launch a separate Eclipse Application from PDE, then test the preference page and Zephyr menu there. Do not develop against a floating latest target and claim the pinned target passed.

## Still pending

Project wizard, automatic setup, full hardware qualifiers/vendor UI, CDT navigation/error markers, Kconfig and Devicetree integration, flash/serial/debug, auto-updates, complete dependency audit/SBOM and GUI/hardware validation. Packaging success is not evidence that these features exist.
