# QuantaDance IntelliJ Plugin – Roadmap (Open Source)

This roadmap lists areas of improvement and future directions for the QuantaDance IntelliJ plugin. It is community‑driven and intended to evolve via issues, pull requests, and discussions. Use this as a living document to propose, track, and refine ideas.

Contributions welcome: If you want to pick up an item, please open/claim an issue. When you have a new idea, open an issue or PR to propose and discuss.

## Recently Completed (Highlights)

- Tooling and Safety
  - Switched to SHA‑only guards for patching with per‑patch expectedText (no version/timestamp gating).
  - PatchFile: bottom‑to‑top range application; optional reformatAfterUpdate and optimizeImportsAfterUpdate; overlap detection (rejectOverlappingPatches=true by default).
  - CreateOrUpdateFile: supports patches; forwards SHA guard and PSI post‑processing flags to PatchFile.
  - ReadFileContent: returns normalized SHA‑256 (fileHashSha256) and content; default maxChars lowered to 6k; caret‑aware windowing retained.
  - ReadPsiBlockAtPosition: new tool to read enclosing PSI block (function/method/class/field/object) at a given position with structured metadata, caret‑aware, thread‑safe commits.
  - GradleSyncTool: added schema properties and background refresh.

- Agent/Manager UX
  - New session triggers agent session reset (previousId cleared).
  - On first turn, bootstrap includes sub‑agents list; startup UI noise removed.
  - All tools are exposed by default (scoping tool removed from registry); Terminal remains disabled by default via setting.
  - Per‑agent allow‑lists (built‑ins/MCP) so manager can assign tools to sub‑agents.

- Serialization/Display
  - ToolRouter wraps String results into structured objects ({"text": ...}) to avoid escape noise.

- Instructions
  - Updated to prefer patch‑in‑place with SHA guard + expectedText; optional PSI reformat/imports; removed version/timestamp guidance.

- Tests
  - PatchFile platform tests updated for SHA‑only guards; added commit/save after tool exec for consistent assertions.

## Near‑Term Improvements (Good First / Low Risk)

- Logging & Debugging
  - [ ] Add a “Verbose logging” toggle in plugin settings to surface debug logs without internal mode.
  - [ ] Document typical failure patterns for patch guards (hash mismatch, expectedText mismatch, overlaps) and suggested recovery.

- Audio/Voice Stability
  - [ ] Make thresholds/durations configurable via plugin settings.
  - [ ] Optional RMS detection (instead of avg abs amplitude) toggle.
  - [ ] Cap maximum phrase duration to avoid unbounded buffers on noisy inputs.

- Tooling UX
  - [ ] Add a ReadFileHeadTail tool (head N / tail N) for very large files.
  - [ ] Optional “reject overlapping patches” preflight command to preview conflicts without applying.

- Validation Flow
  - [ ] Add tests for ReadPsiBlockAtPosition, including caret/line/column coverage and fallback window behavior.
  - [ ] Improve validation summaries (diff‑like snippets around errors).

## Core Enhancements

- PSI‑powered edits
  - [ ] Add PSI element‑level tools (insert/replace method/class/property) using Psi/Kt factories with formatting/imports.
  - [ ] Safe delete (PSI) with preview (RefactoringFactory.createSafeDelete); fall back to VFS only when necessary.
  - [ ] Move/Copy via refactoring processors (MoveFilesOrDirectoriesProcessor, RefactoringFactory.copy) with reference updates.

- Realtime/Multimodal (pending SDK support)
  - [ ] Upgrade com.openai:openai‑java to versions supporting input_audio for multimodal turns when available.
  - [ ] Realtime client path; streaming mic and partial tokens.

- Search & Navigation
  - [ ] Enhance SearchInFiles: case sensitivity toggle, literal mode, filename‑only search, better grouping/previews.

- Embeddings & Indexing
  - [ ] Improve chunking (sliding window + semantic boundaries), byte‑size guard, and max chunk count.
  - [ ] Track per‑file embedding freshness; background re‑index on save/idle; “Re‑index project” command with progress.

- Settings & Preferences
  - [ ] Expose audio thresholds/durations and capture buffer size in settings.
  - [ ] Add verbosity toggles and controls for tool behaviors (e.g., auto‑open updated files, default patch formatting/imports flags, overlap rejection).

## Longer‑Term Directions

- Multimodal Enhancements
  - [ ] Full multimodal turns: input_text + input_image + (when available) input_audio in one message.
  - [ ] Output audio (when supported) for short synthesized replies.

- Realtime UX
  - [ ] Adaptive latency tuning, chunk sizing, and better interruption handling.
  - [ ] Unified voice+text transcript with timestamps in the Tool Window.

- AI‑Assisted Refactor/Review
  - [ ] “Propose & apply” diffs: show diffs for user review and apply accepted changes in one write action.
  - [ ] Cross‑file refactor assistance with dependency awareness.

- Testing & QA
  - [ ] IntelliJ platform test harness for VFS/PSI and Tool Window states.
  - [ ] Snapshot/golden tests for tool output and streamed progress messages.

- Performance & Reliability
  - [ ] Backpressure and chunked uploads for large audio.
  - [ ] Non‑blocking write actions for file operations; queue operations to avoid UI stalls.
  - [ ] Optional telemetry/metrics (opt‑in) to understand failures/hotspots.

## Technical Notes / Conventions

- Logging
  - [x] Use com.intellij.openapi.diagnostic.Logger via QDLog.
  - [x] In internal mode (runIde), QDLog.info echoes to console; otherwise, logs go to idea.log.

- File guards & Patching
  - [x] Single precondition: expectedFileHashSha256 (normalized SHA‑256) from ReadFileContent.
  - [x] Per‑patch expectedText guards; bottom‑to‑top application; stopOnMismatch recommended for atomicity.
  - [x] Overlap detection (rejectOverlappingPatches=true by default) to avoid ambiguous edits.

- Threading
  - [x] Respect IntelliJ read/write action rules; commit documents safely.

## How to Contribute

- Open an issue for discussion; tag proposals with area labels (audio, realtime, responses, ui/ux, tooling, search, embeddings).
- Keep PRs small and incremental; follow IntelliJ read/write action rules.
- Add tests for new utilities/tools; use platform test harness where applicable.

This document is a living roadmap. Please help refine it with your ideas, feedback, and contributions.
