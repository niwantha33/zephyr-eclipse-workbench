# Contributing

The repository is public and contains a working development preview; the feature-completion and production-release gates remain open. See docs/RELEASE_STATUS_2026-10-30.md.

Read docs/ARCHITECTURE.md, docs/ROADMAP.md and docs/COMPATIBILITY.md before implementation.

## Changes

- Keep PRs focused on a roadmap step and describe observable behaviour.
- Use the selected Zephyr checkout as the hardware source of truth.
- Keep machine-local paths in user preferences and portable project settings in Git.
- Reuse upstream tools and report unsupported operations explicitly.
- Document new dependencies and validate their distribution terms.
- Run checks appropriate to the change and report actual results and untested areas.
- Never claim physical hardware support from a build-only test.

Original project contributions are provided under the root Apache-2.0 licence unless explicitly agreed and identified otherwise. Preserve upstream notices for copied/adapted code, and record provenance. Read docs/LICENSING.md and docs/DEPENDENCIES.md before adding dependencies.

Only open-source, commercially usable dependencies without mandatory licence/subscription/royalty fees belong in the core workflow. Pin submodules to reviewed commits and retain their own licences. Proprietary optional exceptions are not currently authorised.

## Hardware reports

Include exact board/SoC/core/revision, host, Zephyr commit, SDK and programming/debug tools, application/configuration, connection/probe, reproduction steps and logs. Remove credentials and personal data from logs.

## Publication

Only publish an installer/update site after the applicable release acceptance checks and the owner's release approval. Do not publish unfinished or unverified features as production-ready.

## README screenshots

The [README](README.md#screenshots) lists reserved PNG screenshot paths; a
placeholder is not evidence that a view has been captured. Only add image links
to the README after the corresponding files are committed.

1. Capture the **actual installed Eclipse Workbench UI**, not a drawing, mock-up
   or generated replacement. Include the Workbench version, OS, target board,
   and build/debug state in the PR description so reviewers can reproduce it.
2. Place clear, legible PNGs at exactly these paths:
   - `docs/images/pin-canvas.png` — Chip Pin Canvas and selected board.
   - `docs/images/config-studio.png` — Configuration Studio with build evidence.
   - `docs/images/register-inspector.png` — SVD view in a suspended session.
3. Redact credentials, user names, private paths, device identifiers and
   proprietary source code before sharing. Use public or synthetic sample
   firmware and configurations; keep material UI warnings visible.
4. Avoid retouching the application's UI or presenting unverified pin mappings,
   peripheral values or debug observations as real hardware results.
5. Include the screenshot files and the README image references in one focused
   PR. Check that all image paths resolve and captions match the capture.
