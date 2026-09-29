# Behavioral Scenario Index

This file points to executable scenarios/tests that act as the primary documentation for important behavior.

## Current executable scenarios

### Workspace file contract flow
- `frontend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/frontend/contracts/FrontendWorkspaceFileClientTest.kt`
  - verifies the frontend workspace file client forwards read/write requests through the shared contract correctly
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/contracts/BackendWorkspaceFileServiceTest.kt`
  - verifies backend workspace file service rejects blank paths with friendly backend errors

### Split-mode settings synchronization
- `backend/src/test/kotlin/com/github/quanta_dance/quanta/plugins/intellij/backend/rpc/BackendSettingsSyncScenarioTest.kt`
  - verifies settings RPC updates backend state and the effective OpenAI connection settings used for fresh clients

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

## Known gaps
- Full end-to-end refresh inside `OpenAIService` is still only partially covered; the current scenario verifies backend state sync and the refreshed connection settings consumed by client creation.
- Review/comment/custom-prompt full backend execution scenarios are still blocked by ongoing migration from placeholder adapters to RPC-backed implementations.
- Chat/session lifecycle still needs targeted behavior scenarios beyond code-level KDoc.
- Some areas are currently documented only through KDoc and architecture notes because test-runtime repair is deferred; those docs should be treated as guidance, not executable proof.

## Maintenance rule
When a behavior becomes important to explain repeatedly, prefer adding or updating an executable scenario here before expanding prose elsewhere.
