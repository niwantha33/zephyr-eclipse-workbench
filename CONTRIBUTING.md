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
