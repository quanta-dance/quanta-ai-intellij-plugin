Summary of changes and next steps:

What I implemented
1) AGENTS.md as canonical project context
    - Added AGENTS.md at repo root and ProjectAgentsFileManager to read it.
    - Wired AGENTS.md into new-session startup for main chat (OpenAIService) and agent sessions (AgentManagerService), so agents receive project instructions immediately.

2) Removed legacy .quantadance AI_CONTEXT.md
    - Deleted ProjectContextFileManager.kt and updated OpenAIService to use AGENTS.md.

3) ReadFileContent enhancement
    - Added fromLine and toLine parameters (1-based inclusive) to ReadFileContent with clear precedence rules and validations.
    - Added platform tests: ReadFileContentPlatformTest covering validation, slicing, line-numbering, and slice-then-truncate behavior.

4) Request-level safety and instrumentation
    - Added Step 0 logging in OpenAIService: logs requestInputs item count, approx char counts, largest item, and previousId null status.
    - Added Step 1 budgeter in OpenAIService: budgetRequestInputs() trims requestInputs deterministically to a MAX_REQUEST_APPROX_CHARS (~60k) while preserving important context (prefix and tail), and falls back to a minimal context if necessary.

5) Conversation summaries and Step 4 soft-reset
    - Added persisted conversationSummaries map to QuantaAISettingsState.QuantaAIState.
    - Implemented heuristic summary helpers and proactive LLM summarization in OpenAIService: generateSummaryWithLlm(), scheduleSummaryIfNeeded().
    - On context-window 400 in main chat, code now generates/stores a summary, resets previousId (server-side thread), rebuilds minimal request (summary + truncated AGENTS.md + bootstrap + user message) and retries once.

6) Agentic proactive summarization and reset
    - Added agent proactive summarization: AgentManagerService now persists agent transcripts under keys "agent:<agentId>", stores/retrieves rolling summaries, injects summaries on new agent threads, and schedules LLM summarization in background.
    - Implemented agent soft-reset-and-retry on context-window errors (one-time retry) mirroring main chat behavior.

7) Misc
    - Added tests for ReadFileContent and validated compilation for updated classes.

Files added/modified/removed (high-level)
- Added: AGENTS.md (repo root), ProjectAgentsFileManager.kt
- Modified: OpenAIService.kt (AGENTS.md injection, instrumentation, budgeter, summaries, soft-reset), ReadFileContent.kt (fromLine/toLine), QuantaAISettingsState.kt (conversationSummaries), AgentManagerService.kt (agent summaries + soft-reset), tests (ReadFileContentPlatformTest)
- Removed: ProjectContextFileManager.kt

Why these changes matter
- AGENTS.md provides a single canonical source of project instructions for agents.
- Budgeting and proactive summarization prevent the “400: input exceeds context window” error by keeping request size under control and resetting server-side state when necessary.
- ReadFileContent parameters let agents request focused windows and avoid repeatedly re-sending file heads.
- Agentic summarization prevents agent conversations from growing unbounded.

Suggested next steps when you return
- Implement Step 2: Tool-output truncation/summarization (high impact) so large file reads, stack traces, and test outputs aren’t persisted verbatim.
- Expose configuration options in settings for budgets and summarization frequency.
- Run full CI: ./gradlew test and ./gradlew build to validate end-to-end.
- Monitor logs (look for CONTEXT_WINDOW_EXCEEDED entries, budgeting logs, and summarization runs) and tune thresholds accordingly.

If you want, in the next session I can implement tool-output truncation (Step 2) and add configuration UI for the budget settings. Have a good break — I’ll be ready to continue when you return.