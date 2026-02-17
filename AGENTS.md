AGENTS.md

Overview

This repository is an IntelliJ plugin project (Kotlin) named Quanta AI Plugin. It contains tools, services, and agent tools that allow AI agents to interact with the project using IDE-like actions (read files, patch files, run gradle, manage embeddings, interact with MCP servers, and more).

Project structure highlights

- build.gradle.kts: Gradle Kotlin build file.
- src/main/kotlin: plugin implementation. Key packages:
  - tools/*: Tool implementations exposed to agents (file IO, patching, search, embeddings, build/test runners, MCP tools, image/sound generation, agent lifecycle tools).
  - services/*: Core services (OpenAI client, embedding service, vector store, project context manager, agent manager, plugin settings).
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

Required capabilities for agents

Agents should be able to:
1) Read project structure: use the GetProjectDetails tool for an initial overview, and ListFiles to enumerate directories.
2) Inspect file content: ReadFileContent and ReadPsiBlockAtPosition for focused context.
3) Propose and apply changes: produce patches for PatchFile or use CreateOrUpdateFile when necessary.
4) Run and validate: use RunGradleBuildTool and RunGradleTestsTool to verify compilation and tests.
5) Work with embeddings and search: SearchInFiles, SearchProjectEmbeddings, UpsertProjectEmbedding and EmbeddingManager service for context indexing.
6) Use MCP tools: McpListServersTool and McpListServerToolsTool to discover and interact with external MCP servers if configured.

Conventions and safety rules (must follow)

- Before calling any file-modifying tool, retrieve latest file content (ReadFileContent) and compute/obtain the expected file hash. Use PatchFile with expectedFileHashSha256 to ensure atomic safety.
- Prefer small, focused patches. When modifying a class or function, patch only the minimal line range required.
- When multiple patches are applied, send them in one call with stopOnMismatch=true for atomicity.
- If a change might affect build or tests, run Gradle build/tests locally using the provided RunGradleBuildTool and RunGradleTestsTool after applying patches.
- Respect existing code style. Use reformatAfterUpdate when applying patches that change formatting.
- If uncertain about intent of the maintainers, ask clarifying questions instead of guessing.

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
   - Use PatchFile with expectedFileHashSha256. If you must replace the entire file, use CreateOrUpdateFile.content but only with clear justification.

4) Validate
   - Run RunGradleBuildTool. If issues are found, run RunGradleTestsTool and inspect stack traces.
   - Use ValidateClassFileTool for single-file compile validation.

5) Commit suggestion
   - Provide a summary of changes and rationales in the agent message. If CI is available, request that maintainers run CI before merging.

Notes on tools available in this repo

- File operations: CreateOrUpdateFile, PatchFile, ReadFileContent, ListFiles, OpenFileInEditor, CopyFileOrDirectory, DeleteFileTool
- Build & tests: RunGradleBuildTool, RunGradleTestsTool, GradleSyncTool
- Project introspection: GetProjectDetails, GetFileReferencesAndDependencies, InspectDependencies
- Embeddings & search: SearchInFiles, SearchProjectEmbeddings, UpsertProjectEmbedding, EmbeddingManager
- Agents & orchestration: AgentCreateTool, AgentSendMessageTool, AgentRemoveTool
- Media: GenerateImage, SoundGeneratorTool

Agent responsibilities for maintainers

- Document changes clearly: every applied patch should include a short explanation, the problem it fixes, and how it was validated.
- Use incremental edits and keep each agent run focused on a single task (one class or function at a time).
- Ensure compatibility with plugin.xml and resources when moving or renaming files.

Contact & further assistance

If additional project-specific guidelines are needed (coding conventions, preferred testing approach, release process), ask the repo maintainers and include their answers here.

Appendix: Quick commands

- Build: ./gradlew build
- Run tests: ./gradlew test
- Run formatter / checks: follow Gradle tasks in build.gradle.kts

End of AGENTS.md
