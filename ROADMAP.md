# QuantaDance IntelliJ Plugin – Roadmap (Open Source)

This roadmap lists areas of improvement and future directions for the QuantaDance IntelliJ plugin. It is community‑driven and intended to evolve via issues, pull requests, and discussions. Use this as a living document to propose, track, and refine ideas.

Contributions welcome: If you want to pick up an item, please open/claim an issue. When you have a new idea, open an issue or PR to propose and discuss.

## Near‑Term Improvements (Good First / Low Risk)

- Logging & Debugging
  - [x] Replace println/System.out with IntelliJ Logger/QDLog across modules.
  - [x] Echo QDLog.info to console in internal mode (runIde) for easier dev.
  - [x] Add runIde JVM args for internal mode and debug categories.
  - [ ] Add a “Verbose logging” toggle in plugin settings to surface debug logs without internal mode.
  - [x] Document logger categories for Help > Diagnostic Tools > Debug Log Settings.

- Audio/Voice Stability
  - [x] Enforce single playback (Player.stop/one active thread) and wire VoiceService to stop previous playback/process.
  - [x] AudioCapture: AtomicBoolean capture state; interrupt/join; stop/flush/close in finally; write only bytesRead; no copy in detector; detailed KDoc.
  - [ ] Make thresholds/durations configurable via plugin settings.
  - [ ] Optional RMS detection (instead of avg abs amplitude) toggle.
  - [ ] Cap maximum phrase duration to avoid unbounded buffers on noisy inputs.

- Tooling UX
  - [x] Maintain single ToolingMessage per long task (e.g., Gradle tests) with streaming progress and elapsed counters.
  - [x] Spinner card without duplicate “(spinner)” text; progress debounced.
  - [x] Add startToolingMessage handle to update one card in place.
  - [x] OpenFileInEditorTool implemented (caret + optional selection support).
  - [ ] PatchFile: decide fate—register with guardrails (validate patches) or remove.

- Validation Flow
  - [x] ValidateClassFileTool.findErrors() exposed for programmatic use; execute() for single Tool Window message.
  - [x] CreateOrUpdateFile calls findErrors() and avoids duplicate ToolWindow messages.

- Testing Basics
  - [ ] Unit tests for VersionUtil and CurrentFileContextProvider.
  - [ ] Unit tests for ReadFileContent version logic.
  - [ ] Tests for Player/VoiceService interaction (stop interrupts previous playback).
  - [ ] CI to run tests on PRs (e.g., GitHub Actions).

## Core Enhancements

- Direct Audio to Responses (model/SDK upgrade)
  - [x] Add sendAudioMessage(wavBytes) scaffolding with safe fallback to transcript()+sendMessage.
  - [ ] Upgrade com.openai:openai-java to a version that supports audio content (input_audio) for multimodal models (e.g., gpt‑4.1/gpt‑5 family).
  - [ ] Implement real input_audio builders (remove fallback) once SDK/API supports it.
  - [ ] Settings: user can choose “Send audio directly” vs “Transcribe then text”.

- Realtime API Path (optional voice streaming)
  - [ ] Add a Realtime client (WebSocket) to stream mic audio and receive partial tokens/audio.
  - [ ] Seed session with instructions + recent context; append final replies back into stored conversation.
  - [ ] Provide cancellation/barge‑in controls in UI.

- Tool Window & Interaction
  - [ ] Rich “Current file context” card (path + caret/selection + version) with open/navigate actions.
  - [x] Unified job cards with progress heartbeat and a recent lines buffer for Gradle tests.
  - [ ] Add pause/cancel to long-running jobs where feasible.

- Embeddings & Indexing
  - [ ] Improve chunking (sliding window + semantic boundaries), byte-size guard, and max chunk count.
  - [ ] Track per-file embedding freshness; background re-index on save/idle; “Re-index project” command with progress.

- Search & Navigation
  - [ ] Enhance SearchInFiles: case sensitivity toggle, literal mode, filename-only search, better grouping/previews.
  - [ ] Add ReadFileHeadTail tool (head N / tail N) for large files.

- Developer Tools
  - [ ] RenameFileTool: atomic rename with validations.
  - [ ] MoveFileOrDirectoryTool: safe move within project (directory trees included).
  - [ ] CreateDirectoryTool: nested dir creation with clear errors.
  - [ ] RunGradleTasksTool: generic Gradle runner reusing the single-message streaming UI.

- Settings & Preferences
  - [ ] Expose audio thresholds/durations and capture buffer size in settings.
  - [ ] Add verbosity toggles and controls for tool behaviors (e.g., auto-open updated files).

## Longer‑Term Directions

- Multimodal Enhancements
  - [ ] Full multimodal turns in Responses: input_text + input_image + (when available) input_audio in one message.
  - [ ] Output audio (when supported) for short synthesized replies.

- Realtime UX
  - [ ] Adaptive latency tuning, chunk sizing, and better interruption handling.
  - [ ] Unified voice+text conversation transcript with timestamps in the Tool Window.

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

- Versioning & File Context
  - [x] Prefer VersionUtil.computeVersion (PSI > Document > VFS) and avoid timeStamp fallback.
  - [x] Use CurrentFileContextProvider for canonical current file path/version/caret/selection.

- Validation
  - [x] Use findErrors() (no UI) vs execute() (single Tool Window message) appropriately; avoid duplicates.

- Audio
  - [x] Capture: 16 kHz mono PCM, amplitude-based detection, minimum speech/pause durations.
  - [x] Playback: Player enforces one active playback; VoiceService stops current before new.

## How to Contribute

- Open an issue for discussion; tag proposals with area labels (audio, realtime, responses, ui/ux, tooling, search, embeddings).
- Keep PRs small and incremental; follow IntelliJ read/write action rules.
- Add tests for new utilities/tools; use platform test harness where applicable.

This document is a living roadmap. Please help refine it with your ideas, feedback, and contributions.
