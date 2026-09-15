# Quanta AI IntelliJ Plugin Roadmap

This roadmap prioritizes the work needed to make Quanta AI a reliable, transparent, and extensible IDE-native agent platform for both local and remote JetBrains workspaces. It is a living document: proposals should be discussed through issues and delivered as small, verifiable pull requests.

## Product direction

Quanta AI is not intended to be only another chat panel. Its differentiators are:

- an explicit `frontend` / `backend` / `shared` architecture designed for JetBrains remote workspaces;
- project-aware execution beside the project, indexes, terminal, build system, and tests;
- configurable OpenAI-compatible providers and enterprise gateways;
- inspectable agent tools, multi-agent orchestration, and MCP integrations;
- local, project-scoped embeddings and retrieval;
- an open-source implementation that teams can audit and customize.

Near-term work should strengthen the trust, reliability, and usability around those capabilities before adding more isolated tools, model names, or visual refinements.

## Recently completed

### Distributed architecture and remote workspaces

- [x] Split the plugin into `:frontend`, `:backend`, and `:shared` Gradle modules.
- [x] Establish typed RPC contracts between frontend UI concerns and backend project execution.
- [x] Move project analysis, file/workspace operations, terminal, build/test, MCP, embeddings, and model orchestration to the backend side.
- [x] Keep durable user-editable settings on the frontend and synchronize a runtime snapshot to the backend.
- [x] Package and verify the split plugin through the root IntelliJ plugin descriptor.

### Agent tooling and extensibility

- [x] Provide guarded file reads and patches using normalized SHA-256 and per-patch `expectedText` checks.
- [x] Add project search, PSI block inspection, dependency/reference inspection, terminal jobs, build/test execution, and scheduled follow-ups.
- [x] Support role-based agents, per-agent built-in/MCP tool allowlists, and dynamic model switching.
- [x] Add MCP integration and local SQLite-backed project embeddings/vector search.

### Recent reliability and UX work

- [x] Synchronize current frontend settings, including the selected model, before sending a chat message.
- [x] Avoid unsafe lazy initialization of the legacy agent registry during concurrent agent publication.
- [x] Improve microphone voice detection, first-word capture, and ordered audio delivery.
- [x] Add validated message-width settings and copy controls for fenced Markdown code blocks.
- [x] Remove the binary-incompatible Compose `SwingPanel` dependency from syntax-highlighted refactoring previews.

## Priority 1 — Release reliability and structured failures

- [ ] Replace raw backend stack traces in chat with concise, actionable, structured errors.
- [ ] Preserve plain-text and non-standard error bodies returned by OpenAI-compatible gateways.
- [ ] Map authentication, unsupported model, rate limit, context limit, timeout, cancellation, RPC disconnect, and tool failures to distinct UI states.
- [ ] Add a **Copy diagnostics** action while keeping full exception details in IDE logs.
- [ ] Remove or migrate the obsolete `AgentRegistryService` and retain one authoritative owner for active agent state.
- [ ] Audit persisted collections and service initialization for concurrent mutation and lifecycle races.
- [ ] Add stress coverage for simultaneous agent completion, wake-up, cancellation, publication, and project disposal.
- [ ] Verify that child agents, terminal jobs, coroutines, and streams terminate on cancellation, project close, and plugin unload.

## Priority 2 — Provider and model capability management

- [ ] Add **Test connection** to settings with clear endpoint, credential, and API compatibility results.
- [ ] Discover available models from providers/gateways when supported, with an advanced custom model-ID option.
- [ ] Represent model capabilities such as Responses API support, tool calling, structured output, reasoning, vision/audio, and context limits.
- [ ] Prevent or clearly warn about selecting a model unavailable from the configured gateway.
- [ ] Refresh capabilities when endpoint, credentials, or provider configuration changes.
- [ ] Keep frontend/backend settings synchronization ordered, observable, and covered by executable scenarios.
- [ ] Add a guided first-run setup flow and presets for supported providers without reducing custom-gateway flexibility.

## Priority 3 — Safe and reviewable agent execution

- [ ] Add an execution timeline showing goals, active step, delegated agents, tool calls, files, commands, and validation results.
- [ ] Show where each operation executes: frontend machine, backend workspace, or external MCP service.
- [ ] Provide first-class diffs with accept/reject per file and per hunk before applying proposed changes.
- [ ] Support reverting an individual agent action and restoring the complete pre-task state.
- [ ] Make destructive, externally visible, credential-sensitive, or policy-restricted actions require explicit approval.
- [ ] Allow retrying a failed step without restarting an otherwise successful multi-step task.
- [ ] Display cancellation, failure, timeout, and disconnection as different outcomes.
- [ ] Optionally expose model, token, latency, and cost information for each turn.

## Priority 4 — Remote-workspace resilience

- [ ] Add a visible frontend/backend connection and synchronization status indicator.
- [ ] Introduce explicit backend readiness/settings-synchronized signaling instead of relying only on startup retries.
- [ ] Recover cleanly from temporary network loss, frontend reconnect, and backend restart.
- [ ] Detect incompatible frontend/backend RPC contract versions and provide an actionable upgrade message.
- [ ] Propagate cancellation and progress reliably across RPC boundaries.
- [ ] Restore conversations and in-progress task state where safe after reconnect or IDE restart.
- [ ] Exercise high-latency links, large streamed responses, multiple remote projects, and project switching in tests.
- [ ] Publish a verified compatibility matrix for local IDEs and supported JetBrains remote-development modes.

## Priority 5 — Context transparency and privacy

- [ ] Add explicit context attachments for selection, file, symbol, directory, commit, issue, terminal output, and image.
- [ ] Display active context as removable and pinnable items with an approximate context budget.
- [ ] Explain which files automatic retrieval selected and why.
- [ ] Add project-level exclusion rules for secrets, generated files, binaries, and sensitive paths.
- [ ] Provide an optional outbound-context preview for privacy and debugging.
- [ ] Improve embedding chunking with semantic boundaries, size limits, freshness tracking, and background re-indexing.
- [ ] Add a visible **Re-index project** action with progress and cancellation.
- [ ] Document what data is sent to providers, retained locally, and written to logs.

## Priority 6 — Editor-native workflows

- [ ] Add selection/symbol actions for explain, fix, refactor, document, and generate tests.
- [ ] Surface AI assistance from inspections, compiler errors, failed tests, and stack traces.
- [ ] Generate commit messages and pull-request descriptions from the actual repository diff.
- [ ] Review local changes, commits, and branches with navigation back to referenced files and symbols.
- [ ] Add PSI element-level insert/replace tools with formatting and import management.
- [ ] Add safe-delete, move, and copy operations through IntelliJ refactoring APIs with preview and reference updates.
- [ ] Decide and document whether inline completion belongs in Quanta AI's product scope; prioritize agent workflows if it does not.

## Priority 7 — Security and enterprise controls

- [ ] Centralize policy for allowed providers, endpoints, tools, commands, paths, network access, and MCP servers.
- [ ] Redact secrets from model requests, tool output, diagnostics, and logs.
- [ ] Add workspace-trust checks and defenses against prompt injection from project files, terminal output, and MCP responses.
- [ ] Add project-scoped permissions and auditable records of tool calls and modifications.
- [ ] Support enterprise proxies, custom certificates, and secure credential storage.
- [ ] Document provider retention, privacy, and threat-model assumptions.

## Priority 8 — Quality, performance, and accessibility

- [ ] Maintain executable behavioral scenarios for remote settings sync, agent concurrency, cancellation, reconnect, and rollback.
- [ ] Add repeatable agent-quality evaluations for repository explanation, safe refactoring, build diagnosis, test generation, and prompt-injection resistance.
- [ ] Track success rate, unnecessary tool calls, incorrect file modifications, latency, and token use across supported models.
- [ ] Add performance coverage for large conversations, indexes, RPC payloads, diffs, terminal logs, and concurrent agents.
- [ ] Add backpressure and bounded buffering for streamed or large payloads.
- [ ] Verify keyboard navigation, screen-reader labels, focus handling, font scaling, high-contrast themes, and reduced motion.
- [ ] Add opt-in operational metrics only after privacy behavior and retention are documented.

## Documentation and adoption

- [ ] Publish a two-minute installation and first-run guide.
- [ ] Add an architecture diagram for `Frontend ↔ typed RPC ↔ Backend ↔ project/indexes/tools`.
- [ ] Publish a remote-workspace tutorial and troubleshooting guide.
- [ ] Add provider/gateway configuration and unsupported-model troubleshooting.
- [ ] Add an MCP tutorial with one complete, reproducible integration.
- [ ] Document tool permissions, terminal safety, context exclusions, and recovery/rollback behavior.
- [ ] Keep module READMEs, architecture docs, behavioral scenarios, and migration notes aligned with executable behavior.

## Contribution and delivery principles

- Open or claim an issue before beginning a roadmap item.
- Migrate or improve one vertical slice at a time: shared contract, backend implementation, frontend experience, then executable verification.
- Prefer small, reversible changes with guarded patches and focused tests.
- Treat tests and executable scenarios as the source of truth for important behavior.
- Run `spotlessCheck` for Kotlin changes and verify affected modules; run `buildPlugin` and plugin verification for release or boundary changes.
- Preserve current behavior when code and documentation conflict, record the mismatch, and make the follow-up explicit.

This roadmap should be reviewed after each release so completed work moves out of active priorities and newly observed reliability or remote-workspace issues are ranked before feature expansion.