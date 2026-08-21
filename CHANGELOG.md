# Changelog

All notable changes to this project will be documented in this file.

## [2026.08.21]

This release makes AI responses easier to read and reuse with rich Markdown rendering, message copying, and an expanded model selection.

### Added
- AI responses now render Markdown headings, emphasis, strikethrough, inline and fenced code, lists, block quotes, horizontal rules, and line breaks.
- Markdown tables support header styling, cell wrapping, inline formatting, escaped pipes, and left, center, or right column alignment.
- Markdown links are clickable, including links with titles and parenthesized destinations; image Markdown provides clickable alternative text without downloading remote images.
- A copy button appears to the left of the message timestamp while hovering over any message and copies the complete original message, including its Markdown source.
- Added GPT-5.6 Cyber to the available chat models.

### Improved
- The message copy action fades in and out smoothly while reserving its layout space, so hovering does not resize messages or shift timestamps.
- Updated the OpenAI Java SDK to 4.52.0.

## [2026.08.03]

This release makes Quanta AI more reliable in modern IntelliJ environments and easier to follow during long, tool-driven tasks.

### Highlights
- Added support for IntelliJ split mode and Remote Development workflows.
- Added image generation and editing, plus short video generation from text prompts.
- Improved agent planning, scheduled follow-ups, terminal jobs, and MCP server integration for longer development tasks.
- Redesigned chat rendering so assistant responses remain separate and consecutive tool executions are grouped until the next user or assistant message.

### Improved
- File reading and editing now use clearer range metadata, SHA-256 guards, safer patch matching, and more actionable validation output.
- Terminal commands now support managed foreground and background jobs, status polling, cancellation, and more reliable PATH resolution.
- MCP tools now report server connection status and provide clearer discovery and error messages.
- Tool cards, progress messages, compaction notices, model selection, and prompt formatting are clearer and more consistent.
- Image editing preserves the selected file path and refreshes saved files in the IDE.
- Plugin packaging, dynamic unload behavior, startup safety, and IntelliJ Plugin Verifier compatibility were strengthened.

### Fixed
- Assistant messages could be appended to an earlier Quanta AI message instead of appearing as separate responses.
- Tool executions could be split or merged at the wrong UI boundary; uninterrupted tool activity is now shown as one group and a new group starts after visible user or assistant output.
- Chat input and history could render or refresh incorrectly during agent activity.
- Project context could disappear while project analysis was running.
- Terminal output, tool names, prompt newlines, file hashes, and delete-tool summaries could be displayed incorrectly.
- MCP, settings synchronization, media tools, and chat recovery now handle unavailable services and transient failures more safely.

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
