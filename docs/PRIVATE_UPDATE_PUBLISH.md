# Publishing the private Zephyr Workbench 0.1.11 update

## Root cause of Eclipse remaining on 0.1.10

The Eclipse update client tracks a private GitHub branch named updates,
NOT the source-code main branch. Its channel.properties still advertises
0.1.10.202610241100. Merging the 0.1.11 source PR does not compile Tycho,
package an Eclipse p2 repository or advance that manifest.

The first build attempt (Actions #40093850097) failed before allocating a
runner: no build/test steps were executed. That cannot be treated as a
successful compilation or an updater defect.

## GitHub Actions release (when private runners are available)

1. Merge the reviewed private-update.yml workflow into main.
2. In GitHub Actions, select "Build and publish private Zephyr Workbench p2 update".
3. Run workflow on main, with publish = true.
4. Python/Java tests, clean Maven/Tycho build and p2 ZIP integrity checks
   must pass. Only then may the publishing job run.
5. The updates branch receives a new immutable versioned release and a
   channel.properties revision pointing at the exact artifact commit.
6. Pushes to main only build a candidate. They never change the update channel.
   Older releases remain available; no force push, public Pages or marketplace.

## Local Windows release when GitHub Actions cannot allocate a runner

On a trusted developer PC use JDK 21, Maven 3.9+, Python 3.11+ and Git.
Use your existing Git Credential Manager authentication for the PRIVATE repo;
never paste credentials in source files or logs.

From PowerShell in a clean local repository checkout:

    git switch main
    git pull --ff-only origin main
    python scripts/publish_private_update.py

This tests core/installer/update components and produces/validates the p2 ZIP
without changing any published channel.

Only after reviewing the local test result, explicitly publish with:

    python scripts/publish_private_update.py --publish

It refuses dirty/unpushed source, downgrades, an existing immutable version,
bad or mismatched SHA256 metadata and unreadable channels. Both release
commits are pushed together after all verification. A failure leaves the
previous channel intact.

## Verify in Eclipse

1. In GitHub, browse the PRIVATE updates branch/channel.properties; the
   version must now be 0.1.11 with a timestamp qualifier.
2. Use Zephyr > About Zephyr Workbench to check currently installed version.
3. Use Zephyr > Check for Workbench Updates and choose Install Update.
4. Complete the standard Eclipse p2 licence/trust installer and restart.
5. Confirm installed 0.1.11 in About, and File > New > Other > Zephyr >
   Zephyr Project, plus File > Import > Zephyr > Existing Application.
6. If private GitHub authentication fails, update the READ-ONLY repository
   access token in Zephyr > Configure Workbench Updates. Never share it.

The 0.1.11 update is a development preview and still needs real Windows GUI
testing. It does not install ESP32-S3 or STM32 compilers, enable arbitrary
hardware, or overwrite Zephyr project sources. The managed SDK first profile
currently includes only qemu_x86/atom.
