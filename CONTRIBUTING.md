# Contributing

The project is currently private and in its planning stage.

Read docs/ARCHITECTURE.md, docs/ROADMAP.md and docs/COMPATIBILITY.md before implementation.

## Changes

- Keep PRs focused on a roadmap step and describe observable behaviour.
- Use the selected Zephyr checkout as the hardware source of truth.
- Keep machine-local paths in user preferences and portable project settings in Git.
- Reuse upstream tools and report unsupported operations explicitly.
- Document new dependencies and validate their distribution terms.
- Run checks appropriate to the change and report actual results and untested areas.
- Never claim physical hardware support from a build-only test.

Select the project licence before accepting external contributions.

## Hardware reports

Include exact board/SoC/core/revision, host, Zephyr commit, SDK and programming/debug tools, application/configuration, connection/probe, reproduction steps and logs. Remove credentials and personal data from logs.

## Publication

Do not change repository visibility or publish an installer/update site without the owner's explicit instruction.
