# Publishing Workbench updates without GitHub-hosted Actions minutes

The update publisher runs after every qualifying merge into `main`, but its
dedicated `self-hosted,linux,x64,zephyr-p2` runner performs the build. This
avoids billed GitHub-hosted runner minutes. The verification and publication
happen in **one job**, with no upload/download of intermediate Actions artifacts.

## One-time setup on a Linux x64 machine

Use an always-on Linux x64 machine, or Ubuntu on the Windows laptop through
WSL2 **while the WSL distribution is running**. WSL installation/boot and
runner registration are one-time manual operations. A sleeping/offline machine
cannot publish.

1. Open [repository Settings → Actions → Runners](https://github.com/niwantha33/zephyr-eclipse-workbench/settings/actions/runners),
   select **New self-hosted runner**, then **Linux / x64**.
2. On the Linux machine install **JDK 21**, **Python 3.10+**, **Git** and
   **Apache Maven 3.9+**. They must be available on `PATH` to the **service
   user**, not only in an interactive shell.
3. Copy and run GitHub's current runner **download and verification commands**
   shown on that setup page. Do not put the registration token in source,
   tickets, logs or chats.
4. When configuring the runner, supply the additional custom label
   **`zephyr-p2`**. The standard `self-hosted`, `linux` and `x64`
   labels are assigned by GitHub.
5. Under the runner installation directory, install/start its systemd service:

   ```bash
   sudo ./svc.sh install
   sudo ./svc.sh start
   sudo ./svc.sh status
   ```

6. Confirm the runner shows **Idle** on the GitHub runner settings page,
   with the four required labels.

On a Windows PC using WSL2, start in PowerShell with `wsl --install -d
Ubuntu` if it is not already installed. In Ubuntu you can install the base
dependencies:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk python3 git curl ca-certificates tar
```

Ubuntu 24.04's standard apt `maven` package may be too old for this build.
Install **Maven 3.9 or newer** from the [official Apache Maven download
page](https://maven.apache.org/download.cgi). Before registering the runner,
check:

```bash
java -version         # 21
python3 --version     # 3.10 or later
mvn -version          # Apache Maven 3.9+ running with Java 21
git --version
```

With WSL, a configured Linux service alone does not guarantee that Windows
will keep the WSL virtual machine alive after Windows sleeps, restarts or
shuts down. Keep WSL running or use an always-on Linux VM/device and configure
startup accordingly.

## What happens after a merge

1. `.github/workflows/private-update.yml` detects code changes on
   `main`.
2. The dedicated runner checks out the exact merge SHA, tests Python/Java
   code, builds the p2 repository with Tycho and verifies the release ZIP.
3. The same job checks that `main` has not advanced, stages a new immutable
   `updates/releases/<qualified-version>/` directory, writes a
   `channel.properties` with SHA-256 hashes and the release commit, and
   pushes both commits to `updates` atomically.
4. Eclipse checks its private update channel as before and offers
   **Install Update**. The user accepts trust/licence and restarts Eclipse.

No additional user-run Python publishing script, manual release copy, or
separate publishing step is necessary. Existing release directories and
existing projects are retained.

## Recover the still-unpublished 0.1.14 merge

The `0.1.14` source was merged to `main` on 2026-10-24, but GitHub's
hosted runner never started, so the update channel remains at
`0.1.13.202610241722`.

After merging this workflow fix and making the dedicated runner **Idle**,
the push to `main` triggers a new run because the workflow file itself is
in the change paths. Alternatively use **Actions → Build and publish private
Zephyr Workbench p2 update → Run workflow** on `main`.

**Do not fabricate the release by copying unbuilt source files**: if
verification or Maven compilation fails, publication stops and the last
working channel version stays unchanged.

## Security and troubleshooting

- The runner is dedicated to this **private** repository and is labelled
  `zephyr-p2`; do not expose it to untrusted pull-request code.
- Use a non-root Linux user with access to the runner workspace, and keep
  the OS, JDK and runner application updated. GitHub's runner normally
  auto-updates.
- Runner **Offline** / job **Queued**: start the Linux runner service and
  WSL/VM; verify labels match exactly.
- **Java/Maven validation failed**: install Java 21 and Maven 3.9+ for the
  service account and restart the service.
- **Build or p2 verification failed**: inspect the job log for the specific
  source/build error; do not advance `channel.properties`.
- **Publishing failed**: verify GitHub Actions write permission and repository
  branch protection for `updates`; the job needs `contents: write`.
- Failed runs leave previous releases available. For a legitimate retry
  of the current commit use `workflow_dispatch`; it does not republish an
  existing identical version.

The separate pull-request development workflow still uses a GitHub-hosted
runner. The cost-free change here covers automatic publishing after a
trusted `main` merge; PR checks are not made trusted local execution.

See [GitHub's self-hosted runner documentation](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners)
and [runner service instructions](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/configure-the-application?platform=linux).
