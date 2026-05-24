# Changelog

All notable changes to this project will be documented in this file.

## [2026.05.24]

### Added
- Configurable OpenAI TTS voice selection when local TTS is disabled.
- Inline voice controls in the settings panel.
- Backend logging for user-submitted chat messages to improve conversation diagnostics.
- More detailed ReadFile metadata, including requested and actual line ranges, truncation flags, and content-availability hints.
- Debug logging for actual tool results.

### Changed
- Tool success titles now prefer explicit tool-provided summaries and otherwise fall back to clearer action text like Reading or Patching.
- ReadFile now clamps oversized end ranges safely and reports what was actually returned.
- Terminal tool exposure now correctly follows the settings toggle.
- Go validation messaging now depends on Go plugin availability and explains when validation is unavailable.
- OpenAI TTS settings are synchronized through frontend, shared DTOs, backend runtime settings, RPC, and backend voice service.
- Tool output truncation is now limited to terminal command output instead of all tools.
- Patch application is more tolerant of harmless indentation-only single-line guard mismatches without allowing semantic drift.
- Agent turn orchestration no longer enforces per-turn tool-call, write-count, same-file-write, or repeated-read guardrails.
- Frontend chat state refresh now uses backend snapshot polling instead of RPC Flow subscriptions that trigger verifyPlugin internal API failures.
- Compose hover handling now opts in explicitly where required by newer experimental pointer APIs.

### Fixed
- Repeated ReadFile regressions caused by generic tool-output truncation corrupting structured tool payloads.
- PatchFile and CreateOrUpdateFile threading/read-access issues in split and RemDev environments.
- RemDev warnings caused by PatchFile implicitly opening editors.
- Duplicate failed-tool error text appearing both in the card header and collapsed content.
- ReadFile and PatchFile deserialization problems caused by missing Jackson constructor/property binding.
- Settings sync could remain stuck in SYNCING when MCP config was unreadable.
- Settings sync now times out cleanly instead of waiting forever for unavailable backend RPC services.
- Oversized Quanta AI tool window stripe icon.
- Incorrect numeric file version reporting in patch/update tool output by using file hashes consistently.
- Unresolved orchestrator summary references left behind after guardrail removal.
- verifyPlugin internal Flow-signature violations for chat/backend RPC descriptors by removing Flow-returning RPC methods.

### Removed
- Duplicated, unused agent chat services and wrappers that were superseded by AgentManagerService.
