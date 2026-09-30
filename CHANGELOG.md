# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Added shared RPC serialization compatibility tests, direct chat-session lifecycle coverage, MCP OAuth authorization-probe scenarios, and editor-action prompt tests.

### Improved
- Review and comment actions now send selected code through the existing chat flow; custom prompts accept user-authored instructions and optional selected-code context.
- Rebuild and close the cached OpenAI client when synchronized endpoint or credential settings change.
- Collect test XML and HTML reports across every Gradle module in one always-run CI artifact.

### Fixed
- Fail closed when terminal execution is disabled or has no configured allowlist, and reject shell chaining, substitution, and redirection in allowlisted command requests.

## [2026.09.29.02]

### Improved
- Replaced deprecated Gradle dependency-resolution APIs and updated documented Kotlin, Gradle, and JVM target versions.
- Replaced deprecated IntelliJ UI APIs and Jackson field iteration calls.

### Removed
- Removed video generation backed by the OpenAI Sora API ahead of its announced shutdown on September 24, 2026.

## [2026.09.29.01]

### Fixed
- Bundled Jackson Core in the split-mode backend module so ACP startup and other backend JSON services load on IDE builds that do not provide Jackson on the backend classpath.
- Replaced the frontend quick-action catalog's Jackson dependency with the IntelliJ-provided Kotlin serialization runtime, preventing frontend startup failures when Jackson is absent from the frontend classpath.

## [2026.09.29]

This release adds autonomous, asynchronous collaboration across Quanta-managed teammates and paired local Quanta IDEs, while making agent turns, ACP rosters, and collaboration UI faster and more reliable.

### Added
- Added localhost-only Quanta ACP pairing for separately opened Quanta IDE projects on the same machine. Projects can be discovered from the Agentic team UI, paired through the existing backend-to-backend ACP handshake, and managed as shared collaborators.
- Added a transport-neutral collaboration roster and task router for the main coordinator, local Quanta teammates, and explicitly paired ACP collaborators.
- Added asynchronous team fan-out/fan-in: independent collaborators can work concurrently, and the main coordinator posts one final synthesis only after every requested report reaches a terminal state.
- Added dependency-aware nested agent tasks so a teammate can request work from another teammate or an ACP collaborator, wait without blocking its worker, and resume with the correlated result.
- Added local, privacy-safe turn-performance telemetry for scheduler wait, request construction, model request, tool execution, and chat-publication phases. Telemetry records timings and counts only; it does not log prompts, responses, source content, paths, tool arguments, or credentials.
- Added a same-machine Shared IDE collaborators picker with a compact configuration popup, manual invite fallback, and explicit collaborator removal.
- Added project-aware shared-collaborator names, live ACP activity indicators, and stable paired-peer identities.
- Added GPT-6 Sol and GPT-6 Luna to the selectable chat models.

### Improved
- Isolated interactive manager turns, background coordination, inbound ACP work, tool execution, catalog refreshes, and chat publication onto bounded project-owned execution paths.
- Made built-in tools, MCP tool metadata/schemas, active-editor hashes, and project context snapshots cache-first so agent turns do not repeat filesystem scans, context reads, or MCP discovery.
- Improved agentic chat coordination with one linear visible manager conversation, per-turn thinking indicators, retained drafts while a turn is active, and clearer collaboration activity.
- Made local/ACP collaboration lifecycle state monotonic, including fast completions, cancellation, restart recovery, and duplicate-result handling.
- Simplified the Agentic team settings UI: shared-IDE connection settings are compact and isolated from the primary agent roster; the entire settings dialog scrolls as one surface.
- Deduplicate discovered ACP applications by resolved executable, preserve a cached roster during background refresh, and keep stable identities for re-paired Quanta collaborators.
- Improved tool cards: Search Files shows its query and filters; List Files shows its directory and can reveal directory targets in the Project view.
- Updated the OpenAI Java SDK to 4.70.0.

### Fixed
- Removed stale typing/glowing agent state after IDE restart and convert interrupted in-flight work to a terminal state.
- Prevented routine roster updates and inbox maintenance from producing unsolicited `Processed inbox messages` responses.
- Prevented manager delegation from blocking on a teammate response, creating partial team summaries, or exposing raw internal delegation tool cards.
- Fixed stale and duplicate ACP roster/discovery entries, including stale local IDE advertisements, self-discovery, and legacy ghost shared peers.
- Fixed shared ACP activity visibility in both the initiating and receiving IDE sessions.
- Fixed invalid List Files directory links and missing Search Files/List Files context in tool cards.

## [2026.09.22]

This release improves agent visibility, ACP discovery, and IntelliJ-platform safety for multi-agent work.

### Added
- Added an agent-presence strip in agentic mode with compact agent avatars, live status badges, animated working indicators, quick status popups, and detailed agent profiles.
- Added click-to-open agent profiles with readable, scrollable current status and instructions for long ACP activity and teammate prompts.
- ACP discovery now imports compatible agent servers configured by JetBrains in `~/.jetbrains/acp.json`, including command arguments and environment overrides.

### Improved
- Restores already authorized ACP teammates automatically when the IDE or chat starts, without starting a persistent ACP work session.
- Keeps the agent-presence strip layout stable while agents start or finish work, and makes working internal teammates visible from their live execution state.
- Keeps the ACP discovery stdio input open until the `initialize` probe completes, improving compatibility with Node-based adapters such as `codex-acp`.

### Fixed
- Corrected IntelliJ document-model access in `PatchFile` and `CreateOrUpdateFile` when agent tools run from background orchestration threads.
- Prevented expected ACP adapter behavior from being misclassified as unavailable during the discovery handshake.

## [2026.09.20]

This release delivers controlled external-agent collaboration, per-chat MCP management, and stronger reliability for AI, MCP, and multi-project workflows.

### Added
- Agentic team roster for discovering ACP-compatible agents and explicitly allowing external ACP teammates only for the current chat.
- Live ACP collaboration cards with discovery progress, streaming activity, meaningful findings, retained-session follow-ups, cancellation, and clear external sign-in guidance.
- MCP Tools control beside the agentic-team control, with per-chat server enablement, server status, tool counts, explicit authorization, discovery progress, and per-server retry.
- Proactive MCP OAuth token refresh with secure Password Safe storage, asynchronous reconnect, bounded retry before expiry, and explicit re-authorization after terminal refresh failure.
- OpenAI request lifecycle diagnostics, bounded connection/header/body deadlines, stale-pool recovery for failed pre-header connections, and automatic transient retries for up to 15 minutes with exponential backoff capped at 60 seconds.

### Improved
- ACP discovery is availability-only: discovery never authorizes, connects, or shares context with an external agent until the user adds it to the active chat.
- ACP agents are independent external collaborators; routine activity remains in a stable task card while meaningful findings can coordinate the main agent and other teammates.
- MCP configuration reload, OAuth handling, discovery, and connection recovery run asynchronously on isolated lifecycle work and do not block chat or the OpenAI request path.
- Configured MCP headers are included in authorization probes, and browser OAuth is offered only after a real HTTP 401 response, supporting GitLab and other custom header-based authentication.
- Loopback HTTP MCP endpoints (`localhost`, `127.0.0.1`, and `[::1]`) are supported while non-loopback MCP endpoints remain HTTPS-only.
- Terminal output, file/document operations, MCP configuration editing, and settings synchronization remain scoped to the intended IntelliJ project when multiple projects are open.

### Fixed
- Added recovery actions for failed local and remote MCP connections without requiring an IDE restart.
- Suppressed expected MCP stdio shutdown races (`Stream closed` and `Broken pipe`) without hiding unexpected transport failures.
- Prevented chat from blocking on browser OAuth or remote MCP discovery.
- Deferred chat-triggered editor navigation to avoid Compose/Swing redraw re-entry failures.
- Guarded PatchFile document lookup with an IntelliJ read action when agent tools run on background threads.

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
