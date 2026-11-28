<!-- SPDX-License-Identifier: Apache-2.0 -->
# Secure development and qualification

Status: foundation proposed; not certified. Reference: [Black Duck seven-layer security checklist](https://assets.blackduck.com/image/upload/ai-coding-assistants-your-seven-layer-security-checklist.pdf). Apply to the Workbench itself, not as a claim of firmware security certification. Preserve the Evidence Engine G0 and all existing release gates.

## Seven-layer controls

| Layer | Required evidence | Initial implementation / outstanding |
| --- | --- | --- |
| Coding standards | AGENTS.md, PR impact and dependency review, least privilege | Policy and review template; implementation audits open |
| Threat modelling | Trust-boundary assessment for each change, threat register below | Initial register; validate against code and tests |
| AI-generated tests | Positive and negative fixtures reviewed by a human; reproduce prior vulnerability | Existing parser/publisher regression tests now run separately; broader tests open |
| AI reviewing AI | Independent review of exact diff, evidence and remediation | Manual independent review procedure; automated provider not authorized/configured |
| SAST/SCA/DAST | Java and Python CodeQL, secret and dependency scan, installed-app hostile inputs | New Python CodeQL, secret scan, managed Python lock scan; Java/p2, firmware C/C++ and DAST coverage open |
| Human review | Enforced independent approval and required checks | Owner must activate ruleset; no self-approval |
| Runtime defence | Workspace trust, process isolation, parser limits, signed update, incident handling | Existing safeguards partial; product hardening and signing open |

## Threat register

| Boundary / assets | Threat | Required control and negative test |
| --- | --- | --- |
| AI prompts / source / private designs | Instruction injection, credential or source disclosure, unsafe suggestions | Untrusted input labeling, no external project data by default, no autonomous merge/flash; test injected instructions without execution |
| PR / Actions / publishing token | Workflow injection, dependency substitution, approval bypass | Read-only PR jobs, pin actions and scanners, trusted required checks, no pull_request_target executing PR code; disposable branch rejection tests |
| Workspace / west / CMake / scripts | Arbitrary execution under developer identity, path or environment hijack | Explicit workspace trust before execution, argument arrays, selected tool paths, environment sanitation; malicious PATH and shell-metacharacter tests on Windows and Linux |
| SDK / wheel / SVD / update downloads | Tampering, rollback, spoofed publisher | Approved origins, digest verification before extraction, independently authenticated signature and version, reject corrupt/missing/older metadata; test tampered and replayed updates |
| ELF / SVD / XML / trace / zip / evidence bundle | XXE, traversal, symlinks, oversized files, malformed metadata | Disable external entities, bounded reads and archive limits, no extraction outside destination; fuzz and regression tests for each parser |
| Debugger / target / serial / RTT | Wrong board, unauthorized register writes, hostile target output, orphan processes | Explicit target identity and writes, untrusted output, owned-process cleanup, bounded capture; test invalid identity and failed launch/cancel |
| Local config / logs / exported evidence | Token, path or customer data leakage | Secure storage, redacted logs, explicit export preview and allowlist; inspect exported bundle and diagnostic logs |
| Dependencies / release / firmware | Known vulnerabilities or licence violations, incomplete provenance | Scan resolved packages, review applicability, SBOM with exact release artifacts, signed build provenance; reject altered artifact and verify signatures |

Risk owner: maintainer. For each changed boundary record affected assets, abuse case, severity, mitigation, test and residual risk in the PR. Never treat a threat register as proof of mitigation.

## Independent review and privacy

An independent reviewer (human, or a separately instructed owner-approved AI as an additional reviewer) checks the exact commit diff against the threats, existing architecture, dependency provenance and negative tests. Findings must identify file/line, impact, evidence, severity and suggested correction; confirm findings against source before acting. Do not send private files, logs, secrets or unpublished designs to an external model. Do not give a reviewer write, release or approval privileges. Record independent human approval on GitHub; AI is not a substitute. Re-review changes after new pushes. Until approved tooling exists, the automated AI review task stays open.

## Automated checks and scope

The security workflow runs on every PR, main push, weekly and manual dispatch, without path filters. It runs Gitleaks (redacted; full checkout history), OSV-Scanner on exact package versions exported from the three managed Windows Python locks, existing hostile-input/publishing regression tests, and Python CodeQL security-extended. Scanner downloads are pinned by version and SHA-256. OSV receives public package identifiers/versions, not workspace contents. The existing Java CodeQL and evidence qualification remain independent checks. CodeQL job success indicates analysis ran, not that all alerts are resolved; configure GitHub code-scanning merge protection separately. Scanner failures are not ignored. Gitleaks reports only rule, path, line, commit and fingerprint in CI; report content is redacted and removed, never uploaded. Historical findings require verification and credential revocation if genuine before any narrow exception is approved.

OSV lock scans cover the recorded Python distributions, not Eclipse/p2, Maven transitives, SDK binaries, Zephyr modules or the application firmware. Resolve those inventories separately; no SBOM completeness or DAST claim is made. The lock export is not a release SBOM. Scan findings must be remediated or subject to explicit narrowly scoped, time-limited owner approval with rationale and evidence; do not add blanket suppressions or weaken CI.

## Owner-only administration

Follow [MAIN_BRANCH_POLICY.md](MAIN_BRANCH_POLICY.md). In repository Settings → Rules → Rulesets, create an active branch ruleset targeting main; require a PR, trusted checks and resolved conversations, block force pushes/deletion, require up-to-date branches, no broad bypass. Require an independent approval and code-owner review with stale approvals dismissed once agent and reviewer identities are distinct. GitHub disallows self-approval. Disable auto-merge and Actions approving PRs. Add exact successful observed check names, including existing Evidence qualification gate, Java CodeQL, and new Security qualification gate; enable code scanning merge protection for high/critical alerts as available. Do not merge until owner confirms rules and checks. Test bypass rejection on a disposable branch, not main. Enable secret scanning/push protection and Dependabot alerts in Settings where available; verify actual state.

## Qualification and incident response

Before stable release, exercise malformed XML, archives, ELF, traces and evidence, workspace trust, environment/argument injection, debug cancellation and update tampering on Windows/Linux; use cross-vendor representative boards and distinguish simulation from physical testing. Produce exact-release resolved SBOM, licence record, authenticated update metadata, signatures and verified provenance. Review active CodeQL/secret/dependency alerts and record unresolved risks. Keep a private incident channel per SECURITY.md; contain compromised updates/tokens, revoke/rotate credentials, identify affected versions, issue a fixed verified release and document recovery. Review weekly scans and after dependency or trust-boundary changes. No security certification is claimed.

## Completion register

| ID | Deliverable | Status at foundation PR |
| --- | --- | --- |
| SEC-01 | Enforced branch protection | Owner configuration and verification required |
| SEC-02/03 | Agent security/privacy rules | Proposed policy and PR template |
| SEC-04/05 | Build/runtime threat model | Initial register; code-level validation open |
| SEC-06 | SAST | Existing Java, proposed Python; quality and C/C++ coverage open |
| SEC-07 | SCA | Proposed Python lock scan; p2/Maven/Zephyr/SDK coverage open |
| SEC-08 | Secret detection | Proposed Gitleaks; GitHub push protection open |
| SEC-09 | Independent AI reviewer | Procedure only; automation open |
| SEC-10 | Security regressions | Existing tests wired separately; negative coverage audit open |
| SEC-11/12/13 | DAST, execution and workspace trust | Open |
| SEC-14/15 | Complete SBOM and signed releases | Open |
| SEC-16/17 | IDE findings and runtime monitoring | Open |
| SEC-18/19 | Incident and governance procedures | Initial policy; rehearsal and enforcement open |
| SEC-20 | Final qualification | Not passed |

### Historical synthetic fixture finding

The only initial Gitleaks finding was generic-api-key in tests/pytest/test_evidence_cli.py, line 53 at commit 5dbee3459aaa47baa1fa4ba4d918c09187915068. The source is a synthetic Kconfig comparison/redaction test; it does not use an API, account or external service. Current fixtures construct distinct harmless markers at runtime and still verify redaction. The exact historical fingerprint is recorded in .gitleaksignore so the full-history scan can remain enabled. This is a false-positive classification, not an exemption for live credentials or a rule/path-wide suppression. Owner review of the classification is required before merge.

### Managed Python advisory remediation (2026-11-28)

The initial OSV run reported eleven vulnerabilities in pip 25.2, setuptools 80.9.0, wheel 0.45.1 and cbor2 5.7.1. The lock refresh selects pip 26.2, setuptools 84.1.0, wheel 0.46.2 and cbor2 5.9.0, matching the highest fixed version reported for each package. Wheel SHA-256 values were cross-checked against the corresponding official PyPI release file metadata, then the exact distributions were downloaded and hash-verified in the PR's Linux CI to obtain byte sizes and licence-file hashes. The existing packaging pin is installed in the offline bootstrap because wheel now requires it. Exact-head OSV, regression and Windows firmware qualification remain required; this record does not certify other dependencies or a release.
