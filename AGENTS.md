AGENTS.md

Overview

This repository is an IntelliJ plugin project (Kotlin) named Quanta AI Plugin. It contains tools, services, and agent
tools that allow AI agents to interact with the project using IDE-like actions (read files, patch files, run gradle,
manage embeddings, interact with MCP servers, and more).

Project structure highlights

- build.gradle.kts: Gradle Kotlin build file.
- docs/: repo-level architecture, migration, and scenario documentation. Start with `docs/architecture-overview.md` for eager context, `docs/behavioral-scenario-index.md` for executable behavior coverage, and `docs/modular-migration-map.md` for ownership/migration status.
- src/main/kotlin: plugin implementation. Key packages:
    - tools/*: Tool implementations exposed to agents (file IO, patching, search, embeddings, build/test runners, MCP
      tools, image/sound generation, agent lifecycle tools).
    - services/*: Core services (OpenAI client, embedding service, vector store, project context manager, agent manager,
      plugin settings).
    - project/*: utilities for extracting file context and dependencies.
    - toolWindow/*, actions/*: UI code and actions.
- resources: plugin.xml and icons.

Important environment & versions

- Java: 21
- Kotlin: 2.2.21
- Kotlin JVM target: 17
- Gradle: 9.3.1

How AI agents should work with this project

Goals for an AI agent working with this repository:

- Understand the plugin's architecture and responsibilities of each package.
- Use provided tools under src/main/kotlin/.../tools to perform safe project modifications.
- Follow the File modification policy: prefer targeted patches (PatchFile) and use expected file hash guards.
- Create clear, incremental, and reversible changes. Avoid large wholesale replacements unless requested and justified.
- Maintain documentation bottom-up: start from executable behavior, then clarify code/API surfaces, then package boundaries, then module READMEs, then repo-level docs.
- For important behavioral questions, treat tests and executable scenarios as the source of truth; docs should summarize intent and coverage, not replace verification.
- When docs disagree with code or tests, preserve the current behavior, document the conflict, and add a TODO near the conflicting code or doc that explains the mismatch and the follow-up refactor needed.
- Prefer Kotlin-friendly documentation: KDoc on public APIs first, package-level KDoc only where package boundaries need explanation, and README.md / DESIGN.md for module- or repo-level guidance.
- Keep package/module docs concise and navigation-friendly for new sessions and AI agents.

Required capabilities for agents

Agents should be able to:

1) Read project structure: use the GetProjectDetails tool for an initial overview, and ListFiles to enumerate directories.
2) Inspect file content: ReadFileContent and ReadPsiBlockAtPosition for focused context.
3) Propose and apply changes: produce patches for PatchFile or use CreateOrUpdateFile when necessary.
4) Run and validate: use RunGradleBuildTool and RunGradleTestsTool to verify compilation and tests.
5) Work with embeddings and search: SearchInFiles, SearchProjectEmbeddings, UpsertProjectEmbedding and EmbeddingManager service for context indexing.
6) Use MCP tools: McpListServersTool and McpListServerToolsTool to discover and interact with external MCP servers if configured.
7) Maintain docs discoverability: every module should have a README.md, important packages should expose their intent through KDoc or a dedicated package-doc file, and repo-level docs should link to the authoritative slice map or migration notes.
8) Follow documentation discovery order for new sessions: `AGENTS.md` -> root `README.md` -> `docs/architecture-overview.md` -> `docs/behavioral-scenario-index.md` -> `docs/modular-migration-map.md` -> module `README.md` files -> tests / executable scenarios for important behavior -> KDoc on key APIs and services.

Conventions and safety rules (must follow)

- Before calling any file-modifying tool, retrieve latest file content (ReadFileContent) and compute/obtain the expected
  file hash. Use PatchFile with expectedFileHashSha256 to ensure atomic safety.
- Prefer small, focused patches. When modifying a class or function, patch only the minimal line range required.
- When multiple patches are applied, send them in one call with stopOnMismatch=true for atomicity.
- If a change might affect build or tests, run Gradle build/tests locally using the provided RunGradleBuildTool and
  RunGradleTestsTool after applying patches.
- Spotless formatting is a hard build gate in this repo. Keep Kotlin formatting Spotless-compliant (indentation,
  wrapping, and avoid introducing extra blank lines).
    - Prefer minimal formatting changes in the touched area; do not apply massive repo-wide reformat unless requested.
    - If Spotless fails, fix formatting via small patches (or run ./gradlew spotlessApply only with maintainer approval
      because it can touch many files).
- Respect existing code style. Use reformatAfterUpdate when applying patches that change formatting.
- If uncertain about intent of the maintainers, ask clarifying questions instead of guessing.
- If you find a package where responsibilities overlap, keep the existing split, add a TODO in code or docs, and
  continue the documentation pass so the conflict stays visible.

Documentation maintenance workflow

- Start from executable behavior and work upward.
- For user-visible or integration-heavy behavior, first verify whether tests or scenario coverage exist; if missing, note the gap and prefer adding or documenting the scenario before writing confident prose.
- Prefer KDoc on public classes, functions, DTOs, and services before adding broader package/module prose.
- Use package-level KDoc only when a package boundary, ownership split, or architectural role needs explicit explanation.
- Keep module README.md files user-facing and concise; place deeper rationale in DESIGN.md or similarly named supporting docs when needed.
- Update docs migration notes when a package or module boundary changes.
- Keep `docs/architecture-overview.md` aligned with the highest-value entry points for new sessions.
- Prefer one authoritative doc per package/module to avoid duplication; if duplication is unavoidable, add a cross-link and a TODO explaining which doc should win later.
- When adding new packages or modules, include the appropriate documentation touch-up in the same change.
- For conflicting scenarios, record the scenario, expected behavior, current behavior, and missing coverage so a future session can close the gap safely.

Development & testing workflow for agents

1) Setup
    - Ensure Java 21 and Gradle are available in the environment.
    - Use the project's Gradle wrapper (./gradlew) for consistent builds.

2) Exploration
    - Run GetProjectDetails to get an overview.
    - Use SearchInFiles to find relevant references.
    - Read target files with ReadFileContent (prefer windowed read for large files).

3) Modify
    - Draft minimal patches and include expectedText guards where possible.
    - Use PatchFile with expectedFileHashSha256. If you must replace the entire file, use CreateOrUpdateFile.content but
      only with clear justification.

4) Validate
    - Run RunGradleBuildTool (or ./gradlew build) to catch compile/test failures and Spotless violations.
    - If issues are found, run RunGradleTestsTool and inspect stack traces.
    - Use ValidateClassFileTool for single-file compile validation.


5) Commit suggestion
    - Provide a summary of changes and rationales in the agent message. If CI is available, request that maintainers run
      CI before merging.

Notes on tools available in this repo

- File operations: CreateOrUpdateFile, PatchFile, ReadFileContent, ListFiles, OpenFileInEditor, CopyFileOrDirectory,
  DeleteFileTool
- Build & tests: RunGradleBuildTool, RunGradleTestsTool, GradleSyncTool
- Project introspection: GetProjectDetails, GetFileReferencesAndDependencies, InspectDependencies
- Embeddings & search: SearchInFiles, SearchProjectEmbeddings, UpsertProjectEmbedding, EmbeddingManager
- Agents & orchestration: AgentCreateTool, AgentSendMessageTool, AgentRemoveTool
- Media: GenerateImage, SoundGeneratorTool

Agent responsibilities for maintainers

- Document changes clearly: every applied patch should include a short explanation, the problem it fixes, and how it was
  validated.
- Use incremental edits and keep each agent run focused on a single task (one class or function at a time).
- Ensure compatibility with plugin.xml and resources when moving or renaming files.

Contact & further assistance

If additional project-specific guidelines are needed (coding conventions, preferred testing approach, release process),
ask the repo maintainers and include their answers here.

Appendix: Quick commands

- Build: ./gradlew build
- Run tests: ./gradlew test
- Run formatter / checks: follow Gradle tasks in build.gradle.kts

End of AGENTS.md
