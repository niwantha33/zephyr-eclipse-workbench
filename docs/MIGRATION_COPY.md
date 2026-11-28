<!-- SPDX-License-Identifier: Apache-2.0 -->
# Safe migration copy and rollback (issue #128)

1. Snapshot the original application and preserve production firmware and its Git revision.
2. Install a separate **ready** candidate environment. A source-only candidate (even with SDK provisioned) is not ready.
3. In Project Environment Manager select Preview Upgrade to inspect differences, then Create Migration Copy. Select a new application directory outside both setups and the original project. Confirm execution of trusted candidate tools.
4. The helper verifies the original lock and frozen manifest, probes the candidate separately and exclusively creates the copy of fingerprinted application files and a new lock. No original build output or Git history is copied; original application and setup binding are untouched.
5. Import the copy explicitly, select the candidate environment and run a pristine build, then run relevant tests and physical checks. The migration-copy result is unbuilt and unpromoted. Do not treat a new lock as a successful build.
6. Roll back by reopening the untouched original project and its original setup. Keep both projects, source locks and production outputs until independent review. No in-place promotion, destructive rollback or automatic rewrite is performed.

An interrupted copy may leave an incomplete destination; inspect it and choose a new empty directory. Do not attach a partial copy. This is a safe-copy UI and destination preflight improvement, not validation of actual cross-version builds or a complete promotion workflow. G0 remains open.
