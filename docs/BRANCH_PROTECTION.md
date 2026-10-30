<!-- SPDX-License-Identifier: Apache-2.0 -->
# Required GitHub branch protection — administrator checklist

**Current enforcement unknown.** The GitHub connector returned HTTP 403
for `main` branch-protection read access. A passing PR job, a merged PR,
or the existence of this document **does not mean protection is enabled**.

A repository administrator should configure `main` under GitHub
**Settings → Rules → Rulesets** (or **Branches → Branch protection
rules**, where available).

Require a pull request, independent review, and required status check:

```text
Evidence qualification gate
```

It is the final job in `.github/workflows/build.yml`. It depends on
successful completion of:

- `package`: all Python/Java tests, genuine GNU/LLVM linker smoke,
  Eclipse packaging, installed native Eclipse smoke and site verification.
- `Native Windows managed firmware matrix`: Windows tests, seven real
  managed Zephyr builds, each Evidence Engine probe and the
  `qualify_firmware_matrix.py` minimum evidence-coverage assertion.

Reviewers must inspect the exact commit's **green** status before
approval/merge. Do **not** allow administrators or auto-merge to
bypass the checks. Consider requiring branch to be up-to-date before
merging, and prohibit force-pushes/deletion of `main` under the chosen
ruleset. Avoid relying on older successful checks from a different SHA.

The PR workflow now triggers on **all** pull requests, including
documentation-only modifications, to avoid path-filtered missing checks.
This is deliberate and may use extra public GitHub Actions capacity.

The separate `private-update.yml` workflow publishes when merged
commits reach `main`. Its own package build does not replace
pre-merge review or the Windows qualification requirement. To prevent
unverified work being published, enable the required branch rule before
merging additional PRs.

**Verification after enabling:** create a disposable draft PR with an
intentionally failing test (no need to merge), confirm that GitHub blocks
merge until **Evidence qualification gate** succeeds, then close the
draft and remove the test branch. Record the ruleset and demonstration
link in the release checklist. No test should intentionally alter
users' hardware or repositories.

Until an administrator verifies actual enforcement, report the status
as **not verified**, and leave new development PRs draft/unmerged.
