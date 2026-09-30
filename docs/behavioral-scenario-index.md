# Behavioral Scenario Index

This file points to executable scenarios/tests that act as the primary documentation for important behavior.

## Current executable scenarios

### Workspace file contract flow
- `frontend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/frontend/contracts/FrontendWorkspaceFileClientTest.kt`
  - verifies the frontend workspace file client forwards read/write requests through the shared contract correctly
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/contracts/BackendWorkspaceFileServiceTest.kt`
  - verifies backend workspace file service rejects blank paths with friendly backend errors

### Settings synchronization and OpenAI client readiness
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/rpc/BackendSettingsRpcApiTest.kt`
  - verifies settings RPC read/write mappings to backend runtime state
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/openai/OpenAIClientProviderTest.kt`
  - verifies OpenAI client creation is blocked until frontend settings sync and succeeds after sync

### Asynchronous collaboration and ACP lifecycle
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/services/CollaborationRouterServiceTest.kt`
  - verifies capability validation, local/ACP task routing, terminal-result normalization, cancellation, and monotonic lifecycle ordering for fast completions
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/chat/TeamDelegationCoordinatorServiceTest.kt`
  - verifies team fan-out waits for every requested task, preserves terminal reports, and schedules one fan-in result despite duplicate or late completions
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/services/NestedTaskTrackerTest.kt`
  - verifies a parent teammate task waits for correlated child work and resumes with successful or failed child reports
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/chat/ChatConversationStateServiceTest.kt`
  - verifies restart recovery removes transient thinking state and converts interrupted active work to terminal state
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/services/LocalQuantaAcpSessionRegistryTest.kt`
  - verifies same-machine local Quanta IDE advertisement discovery, expiration/process cleanup, self filtering, and explicit removal
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/services/AcpAgentRosterIdentityTest.kt`
  - verifies paired Quanta peers keep one stable roster identity across transient endpoint changes

### Shared RPC serialization contracts
- `shared/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/shared/rpc/models/SharedRpcSerializationTest.kt`
  - verifies collaboration participant DTO round-tripping and defaults for fields omitted by older clients

### Selected-code and custom editor actions
- `frontend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/frontend/actions/EditorActionPromptTest.kt`
  - verifies review/comment prompts include selected code context, preserve code formatting, and custom prompts only include selection context when present
- Review, comment, and custom prompts are submitted through the existing chat RPC; responses appear in chat and edits are not applied automatically

### Terminal and MCP security policies
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/tools/system/TerminalCommandPolicyTest.kt`
  - verifies terminal command policy fails closed when disabled or unconfigured and rejects shell chaining/substitution
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/tools/mcp/McpRemoteConnectionPolicyTest.kt` and `McpOAuthAuthorizationProbeTest.kt`
  - verify secure remote MCP transport, OAuth refresh policy, configured authorization-header forwarding, and 401 Bearer challenge handling

### Chat/session lifecycle
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/chat/ChatConversationStateServiceTest.kt`
  - verifies restart recovery, transient-state persistence rules, ACP permissions, and active-session selection/deletion

## Known gaps
- OpenAI client cache tests cover deferred creation before frontend settings sync, credential-change replacement, and closing old clients; a full live `OpenAIService` request/rebuild integration scenario remains uncovered.
- Editor action prompt construction is unit-tested, but UI invocation and end-to-end chat RPC response delivery do not yet have an integration scenario.
- Chat/session state transitions now have direct service tests; async request cancellation and concurrent session-switch behavior still need dedicated scenarios.

## Maintenance rule
When a behavior becomes important to explain repeatedly, prefer adding or updating an executable scenario here before expanding prose elsewhere.
