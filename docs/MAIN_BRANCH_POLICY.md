# Main branch protection

Only the repository owner authorizes and performs merges to `main`. Developers and agents use feature/fix branches and pull requests; they must not approve on behalf of the owner, enable auto-merge, push directly to main, bypass rules or publish releases without explicit authorization.

## GitHub administration (required separately)

Enable an active ruleset for `main`: require pull requests, required passing checks from trusted CI, up-to-date branches and resolved conversations; block force pushes and deletion; no general bypass. Disable auto-merge and Actions approval of PRs. Keep release credentials and branch administration away from automation. Select exact always-running check names from real PR runs, including packaging, Evidence qualification and native Windows managed firmware qualification; do not weaken existing checks.

For contributor/agent PRs under a separate identity, require one approval and code-owner review, dismiss stale approvals, and require approval of the latest reviewable push where feasible. GitHub does not allow authors to approve their own PRs. When agents operate as the owner, mandatory owner review cannot work for their own PRs. Use separate agent identity before enabling that requirement; until then, owner-only manual merging with PR and CI enforcement is an interim procedural policy, not an independent review guarantee. Never give agents owner credentials or a ruleset bypass. If owner-authored PRs need merging under strict review, arrange an independent trusted reviewer and explicitly design that exception without granting a general bypass.

Before activation, audit current rules, workflow triggers and release automation. Test on a disposable protected branch with a non-owner identity: direct push, force push, deletion, failed CI, missing approval, stale approval and successful reviewed PR. Do not test destructive operations against main. Recheck the active main ruleset after rollout. CODEOWNERS and this document alone do not enforce protection.
