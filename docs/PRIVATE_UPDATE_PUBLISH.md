# Automatic private Eclipse Workbench updates

## How it works

A merge to **main** triggers `.github/workflows/private-update.yml` when
Workbench source, build, test, packaging or the workflow itself changes.

1. GitHub Actions checks out the exact merged source commit.
2. Run the existing Python and Java regression tests, then a clean Maven/Tycho
   package build and packaged Eclipse p2 site verification.
3. Retain the verified p2 archive as a private Actions artifact.
4. If **every** verification step passed and the build still matches the
   current `main` commit, the next job downloads **that exact artifact**.
5. Refuse an older/equal version or a pre-existing release directory.
   Add the new p2 files as one immutable commit in the PRIVATE
   `updates` branch; use that commit SHA in `channel.properties`.
6. Recheck the manifest sizes and SHA-256 of every published file.
   Commit the manifest and push both commits in **one fast-forward push**.
7. Eclipse's existing update checker reads the new private channel version
   and shows **Install Update**. Eclipse's native p2 installer handles
   installation, licence/trust prompts and restart.

There is **no user-run Python script, local publishing command, manual artifact
copy or extra release/publish action**. The update itself remains a user-visible
Eclipse p2 install prompt to avoid interrupting open projects.

An optional Actions "Run workflow" on `main` supports recovery from an
infrastructure interruption; normal merges do not need it. PRs only run
verification and never update the channel.

## Why version 0.1.11 was missing on 24 October 2026

The Zephyr project-wizard source was merged into `main`, but the independent
private `updates/channel.properties` still advertised
`0.1.10.202610241100`. The earlier CI run (#40093850097) ended before it
was allocated a GitHub-hosted runner (zero steps, no log), so no build/site
could be published. This is a build-service blocker, not an Eclipse updater
version comparison bug.

**Important:** If GitHub Actions still refuses to allocate a runner, no
repository workflow can complete. Resolve private-repository Actions access,
billing/usage restrictions or hosted-runner availability in GitHub's Actions
settings. The workflow does not bypass disabled runner execution.

## After a successful automatic publish

In GitHub, select the private `updates` branch and confirm
`channel.properties` advertises `0.1.11.<timestamp>` and an immutable
artifact commit SHA. Older `releases/0.1.10...` directories remain present.

In Eclipse:

1. **Zephyr > About Zephyr Workbench** displays the installed version.
2. **Zephyr > Check for Workbench Updates** should detect the new version.
   With automatic checks enabled, Eclipse checks on startup and periodically.
3. Choose **Install Update**, follow Eclipse's normal trust/licence wizard and
   restart Eclipse when prompted.
4. Reopen About and check for **File > New > Other > Zephyr > Zephyr Project**
   and **File > Import > Zephyr > Existing Zephyr Application**.

If authentication fails, review the private repository read-only token in
**Zephyr > Configure Workbench Updates**. Never share credentials or commit
them. Stable version claims still require native Windows and GUI acceptance;
0.1.11 is a development preview with only the qemu_x86/atom compiler profile.
