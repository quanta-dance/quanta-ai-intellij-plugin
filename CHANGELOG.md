CHANGELOG

All notable changes to this project will be documented in this file.

The format follows "Keep a Changelog" principles with sections for Unreleased and versioned entries (tags).

[2026.04.26] - 2026-04-26

Added

- Update OpenAI client. (3eaaeb3)
- Follow plan and choose model buttons in the UI. (e364a16)
- Plan and subagent features to support multi-agent plans and workflows. (cafe77f, 1a1441d)
- Configurable terminal commands. (22a6c99)
- Feature flag for saving tokens on input context. (96f408c)
- Tokens counter and related debugging options. (a2c1921, 2855d96, 8449c90)
- ReadFileContent enhancement: support reading from/to in addition to window reads. (137b1ef)
- Use AGENTS.md as a canonical project context source. (8155267)

Changed

- Replaced git4idea with jgit to improve repository interactions. (8fdc3e9)
- Improved communication between agents (refactors and enhancements). (4024ef0)

Fixed

- Fixed agents display when adding/deleting agents. (e93313d)
- Fixed scheduling and newline patch issues. (882b7cc)
- Fixed restore of history after IDE reopen. (b471699)
- Fixed communication issues between agents and trimmed long discussions. (e47fc16, ee4c6d6)
- Fixed various deprecations. (8ce7fea)

Chore

- Updated changelog and project documentation. (8da3197)

[2026.01.08] - 2026-01-08

This release includes improvements and fixes made up to commit fe82903.

Added / Changed / Fixed

- Improvements (release commit). (fe82903)
- Plugin verification and packaging readiness. (9c376e2)
- Compatibility fixes for IDEA IU-261.17801.55. (7fdc3c1)
- Deprecation fixes and small cleanups. (4bcdad6)

[2025.12.03] - 2025-12-03

- Update badge in README for JetBrains Marketplace. (31f2ce8)
- Update README.md (26f8227)

[2025.12.01] - 2025-12-01

- Fix: do not send the whole history, only lastResponseId. (1c789a8)
- Fix selection of gpt-5.1 models. (bead416)
- Add icon for light mode. (b6cacdc)
- Initial plugin commit and repository bootstrap. (8e6ae01, e3d62ac)

Notes

- Commit hashes are included for traceability. If you'd like each commit expanded into a one-line description (rather
  than a grouped summary), I can expand them. Also indicate if you prefer different version numbers or release dates for
  any tag mapping.
