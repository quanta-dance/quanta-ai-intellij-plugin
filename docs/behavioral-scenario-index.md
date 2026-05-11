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

## Known gaps
- Full end-to-end refresh inside `OpenAIService` is still only partially covered; the current scenario verifies backend state sync and the refreshed connection settings consumed by client creation.
- Review/comment/custom-prompt full backend execution scenarios are still blocked by ongoing migration from placeholder adapters to RPC-backed implementations.
- Chat/session lifecycle still needs targeted behavior scenarios beyond code-level KDoc.

## Maintenance rule
When a behavior becomes important to explain repeatedly, prefer adding or updating an executable scenario here before expanding prose elsewhere.
