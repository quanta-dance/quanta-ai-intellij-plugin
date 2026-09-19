# Changelog

All notable changes to this project will be documented in this file.

## [2026.09.17.05]

This release adds explicit, chat-scoped controls for external ACP teammates and MCP tools, hardens MCP/OAuth lifecycle recovery, and improves OpenAI transport resilience and diagnostics.

### Added
- Agentic-team roster for discovering ACP-compatible agents, showing discovery progress, and explicitly adding or removing external ACP agents for the current chat.
- Live ACP collaboration cards with background status, streamed activity, meaningful findings, action-required states, cancellation, and retained-session follow-up messaging.
- Per-chat MCP server enablement, an MCP Tools toolbar button, connection/tool-count status, explicit OAuth authorization, configuration/discovery loading feedback, and per-server retry controls.
- OAuth refresh scheduling for MCP access tokens with secure Password Safe persistence, asynchronous reconnect on refresh, retry before expiry, and explicit re-authorization after terminal refresh failure.
- OpenAI request timing and transport-generation diagnostics, bounded connection/header/body deadlines, stale-pool recovery after pre-header connection failures, and automatic transient-request retry for up to 15 minutes with exponential backoff capped at 60 seconds.

### Improved
- ACP discovery is availability-only; external agents must be explicitly approved per chat before the main agent can share context, delegate work, or send follow-ups.
- ACP agents are presented as independent external collaborators rather than read-only workers; routine progress remains on the task card while curated findings can coordinate the main agent and other teammates.
- MCP configuration reload and initial discovery now run asynchronously on an isolated lifecycle executor, and cached remote tool lists avoid repeated `tools/list` discovery before every model turn.
- Configured MCP headers are sent on the authorization probe; OAuth is offered only after an actual HTTP 401 response, supporting GitLab and custom header-based authentication without hard-coded header names.
- Loopback HTTP MCP endpoints (`localhost`, `127.0.0.1`, and `[::1]`) are supported while remote MCP endpoints remain HTTPS-only.
- Terminal output, targeted document saves, settings synchronization, and shared MCP configuration editing now remain scoped to the originating IntelliJ project when multiple projects are open.
- The prompt toolbar now uses compact, discoverable AI/team and MCP tools controls with accessible descriptions and tooltips.

### Fixed
- Prevented chat from blocking on browser OAuth or remote MCP discovery; failed OAuth now leaves the affected server recoverable from the MCP Tools panel.
- Suppressed expected Reactor dropped errors caused by normal MCP stdio shutdown races (`Stream closed` and `Broken pipe`) without suppressing unexpected transport failures.
- Added recovery for local and remote MCP connection failures without requiring an IDE restart.
- Fixed incorrect cross-project terminal console output and arbitrary first-project selection in MCP configuration editing/settings synchronization.
- Deferred chat-triggered editor navigation to avoid Compose/Swing redraw re-entry failures.
- Replaced raw OpenAI timeout stack traces in chat with an in-place, user-facing automatic retry status and countdown.

## [2026.09.16]

This hotfix corrects packaging and Reactor context propagation for remote MCP connections.

### Fixed
- Excluded OpenAI's unused OkHttp transport classes from the backend module JAR so Marketplace validation does not report unresolved `okhttp3` classes.
- Restored Micrometer's Reactor context accessor service registration and registers the accessor explicitly for IntelliJ's isolated backend classloader, preventing remote MCP initialization failures.
- Adjusted the flattened MCP runtime packaging so Plugin Verifier passes without an ignored-problems baseline.

## [2026.09.15]

This release modernizes remote MCP connectivity, adds standards-based OAuth authorization, improves chat reliability, and updates the OpenAI Java SDK.

### Added
- Remote MCP servers now support challenge-driven OAuth Authorization Code with PKCE, including protected-resource discovery and browser-based sign-in.
- OAuth access and refresh tokens, plus dynamically registered public client IDs, are stored in IntelliJ Password Safe instead of MCP configuration files.
- Added `docs/mcp-configuration.md`, covering local stdio servers, remote Streamable HTTP servers, OAuth, credential handling, and troubleshooting.

### Improved
- Migrated MCP support from the Kotlin SDK to the official Java SDK, avoiding Kotlin compiler metadata incompatibilities.
- Remote URL-based MCP servers use Streamable HTTP with correct base-origin and endpoint-path handling for path-mounted services.
- MCP OAuth connection, retry, and safe diagnostic behavior is clearer and avoids duplicate browser authorization prompts.
- Updated the OpenAI Java SDK to 4.63.2.

### Fixed
- Fixed MCP JSON mapper and schema-validator loading under IntelliJ's isolated plugin classloader.
- Fixed a Jackson creator conflict that could prevent built-in tool schema generation.
- Synchronized the selected model before each chat request and prevented concurrent legacy agent-registry initialization.

## [2026.09.04]

This release improves chat readability and reuse, stabilizes microphone capture, and restores compatibility with upcoming IntelliJ IDEA 2026.3 builds.

### Added
- Message width is configurable from 420 to 2000 dp, with digits-only input and validation in plugin settings.
- Fenced Markdown code blocks now provide a hover copy button that copies only the raw code, without fences or the language identifier.
- Bare HTTP and HTTPS URLs in Markdown messages are now clickable.

### Improved
- Microphone capture now preserves the beginning of speech with a short pre-roll and sends audio chunks to the backend in strict FIFO order.
- Syntax-highlighted refactoring previews now use native Compose rendering while retaining IntelliJ-aware highlighting, line numbers, selection, scrolling, and theme colors.

### Fixed
- Removed the binary-incompatible Compose Desktop `SwingPanel` call that could cause `NoSuchMethodError` on IntelliJ IDEA IU-263.3889.65.
- Markdown identifiers containing underscores are preserved instead of being interpreted as emphasis.
- Stabilized terminal job timing tests in CI.

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
