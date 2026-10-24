# Free automatic Eclipse updates using public GitHub Actions

## Why the installed Workbench still says 0.1.13

The current Eclipse channel on the `updates` branch advertises
`0.1.13.202610241722`. The 0.1.14 source was merged into `main`, but
no compiled 0.1.14 p2 site has been published. The earlier hosted Actions
job never received a runner; changing source alone cannot update Eclipse.

GitHub provides **standard GitHub-hosted Actions runners free of charge for
public repositories**. This workflow targets `ubuntu-24.04` and avoids both
private-repository minute consumption and the need for a self-hosted runner.

## One-time change, if you are comfortable making the source public

1. **Before changing visibility**, audit *all branches and Git history* for
   credentials, private customer details, proprietary code and confidential
   artifacts. In particular, the `updates` branch contains older binaries
   that will also become public. Visibility changes publish previous commit
   history and Actions logs; changing back to private cannot undo copies or
   forks that others already made.
2. In repository **Settings → General → Danger Zone → Change repository
   visibility**, select **Public** and confirm GitHub's warnings.
3. Merge the hosted-runner workflow fix. The merge changes
   `.github/workflows/private-update.yml`, which triggers the build on
   `main` automatically.
4. Under **Actions**, confirm **Build and publish Zephyr Workbench p2
   update** completes successfully. A failing compiler/test/packaging job
   must be fixed instead of bypassed. If a new run does not start, use
   **Run workflow** against `main`.
5. Verify that `updates/channel.properties` points to a newer version and
   `updates/releases/<version>/` contains packaged p2 artifacts. Then
   run **Zephyr → Check for Workbench Updates** in Eclipse, accept
   **Install Update**, and restart.

The former self-hosted publish workflow run can remain queued after the
visibility change; the new public workflow uses a different concurrency
group so the stalled old run cannot block the new one. You can manually
cancel the old queued run in GitHub Actions if desired.

## What the repository publishes

The single publishing job:

- Checks out the exact `main` merge commit.
- Uses the runner's JDK 21 plus Python, Maven and Git.
- Runs the existing Python/Java regression suite.
- Builds and verifies the Tycho/Eclipse p2 repository.
- Refuses to publish if `main` has advanced, the version did not
  increase, or a version folder already exists.
- Commits an immutable release and checksummed `channel.properties`
  to `updates` in one fast-forward push, retaining old releases.

There is **no separate user-run Python script or manual release upload**.
The Eclipse installer still asks the user to approve installation and
restart. No binaries will be published if the build or verification fails.

The current Workbench already supports authenticated updates. Making
the repository public does not require changing its existing Eclipse
update URL. Its configured token can be left in Eclipse secure storage
or removed after confirming unauthenticated public-channel access works.

References:
- https://docs.github.com/en/actions/concepts/billing-and-usage
- https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility
