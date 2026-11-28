# CI qualification and owner review
Baseline: d098bf4e4d6f9b3d0ee1826369823e08df91efcc. Publication, Windows firmware qualification, CodeQL and Pages completed successfully on that SHA. This is development-preview evidence, not physical-device certification.

| Task | Implementation / remaining evidence |
| --- | --- |
| CI-01 | Preserve tested bounded Tycho retry; add exact-input Maven repository cache to packaging and publishing. Verify cold and warm runner behaviour. |
| CI-02 | Run full pre-merge qualification on pushes to main as well as PRs. Confirm exact SHA after approved merge. |
| CI-03 | Decode upstream Java extraction telemetry; fail below 85% for either metric or if evidence is missing. Confirm actual metrics on PR. |
| CI-04 | Owner must enable main protection; currently disabled. A repository workflow cannot enforce review on its own. |
| CI-05 | Keep existing core, native UI, Python and firmware tests; add CI evidence negative/positive tests. Full feature inventory and physical regression still pending. |
| CI-06 | Pin Pages actions and enforce direct action/container pins. Existing package integrity checks retained. Transitive dependency inventory, vulnerability review and licence audit still required. |
| CI-07 | Cache Maven artifacts keyed to dependency inputs; no test removed. Measure cold and warm duration before claiming savings. |
| CI-08 | Add exact-SHA qualification, CodeQL metrics and publication summaries to Actions. Hardware status explicitly not tested; no production approval. |

## Owner-only repository protection
Open Settings → Branches (or Rules → Rulesets). Target main only, require a pull request and one independent approval, dismiss stale approvals, require approval of the latest reviewable push and resolved conversations, require up-to-date branches and the checks `Evidence qualification gate` and `CodeQL (Java)` from GitHub Actions. Disable force pushes and deletions and do not allow bypass, including administrators. Verify the check names on the PR before saving. Do not apply these rules to the updates branch used by the isolated publisher. Ensure an independent reviewer is available: authors cannot approve their own pull requests. Do not merge until settings and exact-head checks are verified.

The Actions summaries are per-run evidence, not an aggregated live product dashboard. Physical hardware needs separate board/firmware/commit-bound evidence. G0 remains open.
