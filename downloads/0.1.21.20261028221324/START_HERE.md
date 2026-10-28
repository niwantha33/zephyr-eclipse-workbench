# Install Zephyr Eclipse Workbench from the beginning

This guide describes the Windows x64 development preview. The download is an
Eclipse plug-in archive, not a standalone Windows installer. Eclipse, Zephyr SDKs,
probe software and Trace Compass are separate components.

## 1. Install Eclipse first

Download **Eclipse IDE for Embedded C/C++ Developers 2026-09 R**, Windows x86_64,
from the [official Eclipse package page](https://www.eclipse.org/downloads/packages/release/2026-09/r/eclipse-ide-embedded-cc-developers).
The file is `eclipse-embedcpp-2026-09-R-win32-x86_64.zip`.
Extract the whole ZIP into a writable folder such as `C:\Tools\EclipseZephyr`.
Open `eclipse.exe` and choose a new workspace folder for your projects.

Use the Embedded C/C++ package so CDT and the GDB debug UI are available.
The Workbench plug-in targets Java 21. If Eclipse reports a missing Java runtime,
follow that Eclipse package's Java requirement using an open-source JDK.

## 2. Install the Workbench plug-in second

1. Open this repository's [GitHub Releases](https://github.com/niwantha33/zephyr-eclipse-workbench/releases).
2. Read `START_HERE.md`, then download the asset whose name ends in `_p2.zip`.
   GitHub's automatically generated **Source code** archives are for developers;
   they are not the installable plug-in.
3. In Eclipse select **Help > Install New Software > Add > Archive**.
4. Select the downloaded `_p2.zip` without extracting it. Name it **Zephyr Workbench**.
5. Select **Zephyr Eclipse Workbench (development preview)**, continue through
   the installation details and licence review, and restart Eclipse.
6. Confirm the **Zephyr** menu appears. Open **Zephyr > About Zephyr Workbench**
   to check the installed version.

The plug-ins are unsigned. Review Eclipse's normal trust prompt; do not disable
Eclipse trust checks globally. `SHA256SUMS.txt` detects download corruption and
does not replace publisher signing. No Maven, source build or manually installed
Python is needed for this plug-in installation.

## 3. Let the setup wizard install the build tools

1. Select **Zephyr > Set Up Zephyr**, or accept the new-workspace welcome prompt.
2. Choose an empty, writable folder, for example `C:\ZephyrWorkbench`.
3. Review the installation plan and licences, then start setup.
4. Keep Eclipse open while it downloads the pinned tools and builds HelloZephyr.
5. Wait for setup to report success and open `src/main.c`.

This managed setup installs its own Python, Git, CMake, Ninja, Zephyr checkout and
initial SDK/compiler environment. It does not require global PATH changes.
Allow several gigabytes of free space and Internet access. Additional targets
need more toolchains, source modules and storage. Existing CCS or other IDE
installations do not need to be removed.

The first setup profile verifies a `qemu_x86/atom` build. That verifies the build
environment; it does not flash or test a physical board.

## 4. Select your actual board and build

1. Use **File > New > Other > Zephyr > Zephyr Project**, or import an existing
   application with **File > Import > Zephyr > Existing Zephyr Application**.
2. Open the project's **Properties > Zephyr**. Select the exact vendor, board,
   SoC/core and available qualifiers for your hardware.
3. Choose **Application settings**, **Debug (-Og)** or **Release (-O2)**.
   Debug and Release use separate build folders.
4. Build the project and wait for the verified firmware output message.

Supported missing compiler, HAL and build-host dependencies are prepared from
the selected target metadata. A board appearing in the catalogue is not proof
that its physical flash/debug/trace combination has been validated.

## 5. Prepare the probe before Flash or Debug

For OpenOCD boards, open **Zephyr > Manage Shared OpenOCD Debug Tools** and select
a compatible executable and scripts for that target. The generic SDK OpenOCD
does not necessarily contain every vendor's target support. For example,
MSPM0 needs a build containing `target/ti_mspm0.cfg`.

Connect the board and any required probe driver, then Flash. Close other debug
sessions that own the same probe before starting Debug or trace capture. Runner
and probe requirements depend on the selected hardware; use the displayed
missing-tool guidance rather than changing to an unrelated target configuration.

## 6. Optional RTOS tracing

Trace Compass is not bundled in the Workbench archive. Install its Eclipse
plug-ins through **Help > Install New Software**, using the stable update site
listed on the [official Trace Compass downloads page](https://eclipse.dev/tracecompass/download.html):

`https://download.eclipse.org/tracecompass/stable/repository/`

Select the Trace Compass feature offered there and restart Eclipse. Alternatively,
use the standalone Trace Compass application. The observed working setup used
Trace Compass 12.1.0; future versions require their own compatibility checks.

For a supported CTF RAM build, add to the application's `prj.conf`:

```ini
CONFIG_DEBUG_THREAD_INFO=y
CONFIG_THREAD_NAME=y
CONFIG_TRACING=y
CONFIG_TRACING_CTF=y
CONFIG_TRACING_BACKEND_RAM=y
CONFIG_TRACING_SYNC=y
CONFIG_RAM_TRACING_BUFFER_SIZE=4096
```

Build and flash that same configuration, terminate its debug session, then use
**Zephyr > Capture RTOS Trace (Preview)**. This preview uses OpenOCD, resets and
runs the target briefly, and leaves it halted. It needs no Python scripting
support inside GDB. Import the newest capture's `ctf` folder from `.zew-traces`
into Trace Compass and import its `Zephyr_RTOS_Timeline.xml` analysis once.
Open the trace, then **Views > Zephyr scheduling > Zephyr RTOS timeline**.

The RAM buffer size must fit the board. This is a short snapshot, not unlimited
recording. The current preview does not verify flashed firmware identity or
buffer fullness. Green means scheduled, including interrupt time; grey means
not scheduled and does not identify ready, blocked or sleeping states.

## Future Workbench updates

Select **Zephyr > Configure Workbench Updates** and choose **Use public access
without a token** for this public repository. Then use **Zephyr > Check for
Workbench Updates**, review the proposed installation and restart when asked.
These updates change Workbench plug-ins; they do not silently upgrade your
project's pinned Zephyr source or SDK.

## Evidence and remaining release work

The 0.1.21 preview passed Eclipse/Tycho/p2 packaging, native Windows tests, and
six Windows firmware build cases: TI MSPM0, STM32 F446RE, Nordic nRF52840,
ESP32-S3, ESP32-C3 and non-Espressif MCUboot. MSPM0 capture and timeline viewing
were observed on physical hardware. That is not all-vendor hardware validation.

Before a stable release we still need a clean-machine desktop installation test,
upgrade/recovery checks, a documented probe/target capability matrix, broader
physical flash/debug/trace tests and reliable capture-quality reporting.
