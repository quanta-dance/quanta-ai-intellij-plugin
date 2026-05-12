# Architecture Overview

Quanta AI is a split IntelliJ plugin organized into three Gradle modules:

- `:shared` — transport-neutral contracts, serializers, and RPC API definitions
- `:frontend` — UI, tool window, settings UI, user actions, and frontend adapters
- `:backend` — project analysis, file/workspace logic, OpenAI orchestration, MCP/build/test integrations, and backend persistence

## Discovery order for a new session
1. Read `AGENTS.md` for working rules, documentation conventions, and maintenance guidance.
2. Read the root `README.md` for product-level orientation.
3. Read `docs/architecture-overview.md` and `docs/modular-migration-map.md` for architecture, ownership, and migration intent.
4. Read module `README.md` files in `backend/`, `frontend/`, and `shared/`.
5. For important behavioral questions, inspect `docs/behavioral-scenario-index.md`, integration tests, executable scenarios, or other verification artifacts before relying on prose.
6. Then inspect Kotlin APIs directly, starting with KDoc on public services, DTOs, RPC APIs, and entry points.

## Documentation conventions
- Use tests and executable scenarios as the source of truth for important behavior.
- Use KDoc for public Kotlin APIs.
- Use package-level documentation only when a package boundary or ownership split needs explicit explanation.
- Use module `README.md` files as concise entry points.
- Use `docs/` for repo-level architecture, migration, and process notes.
- When behavior coverage is incomplete, document the gap explicitly rather than implying certainty.

## Remote / split-mode notes
- Frontend settings are synchronized to the backend through RPC.
- In remote split mode, both frontend and backend parts of the plugin matter: backend owns execution and analysis, frontend owns UI.
- Key code entry points for this flow include `QuantaSettingsApi`, `FrontendSettingsRpcService`, `BackendSettingsRpcApi`, `OpenAIClientProvider`, and `OpenAIService`.
- See `BackendSettingsSyncScenarioTest` for the current executable scenario that verifies backend settings sync and refreshed OpenAI connection settings.
- If settings-related runtime behavior changes, update both the owning module docs and `AGENTS.md` when the maintenance strategy changes.

## Documentation maintenance order
1. Verify behavior through integration tests, scenario coverage, or other executable evidence.
2. Document code/API surfaces with KDoc.
3. Clarify package or module ownership where needed.
4. Update repo-level docs after the lower-level facts are stable.

## Tool selection heuristics
- Prefer `ReadFile` for raw file or line-range content.
- Prefer `ReadPsiBlockAtPosition` when you need a semantic block such as a function, class, or field.
- Prefer `PatchFile` for atomic guarded edits and `CreateOrUpdateFile` when whole-file replacement or mixed patch/write behavior is more appropriate.
- Prefer `RunGradleBuildTool` for compile/build verification, `RunGradleTestsTool` for test execution summaries, and `GetTestInfoTool` for drilling into one specific failed test.
- Prefer `SessionPlanTool` for durable cooperative planning and `ScheduleTaskTool` for time-based follow-up reminders inside the current IDE session.

## Known active migration theme
The repository is still converging on clearer ownership between frontend, backend, and shared packages. When boundaries are unclear, document the current behavior first, call out conflicting scenarios or missing coverage, and add a TODO before refactoring.