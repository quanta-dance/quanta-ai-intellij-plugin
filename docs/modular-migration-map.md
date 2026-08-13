# Modular Migration Map

## Current state
- Split-plugin scaffold is green: `:shared`, `:frontend`, and `:backend` build and package successfully.
- Root plugin descriptor links module descriptors via `<content>`.
- Minimal RPC example compiles across modules.

## Prioritized migration slices

### Slice 1 — Terminal and workspace file operations
**Why first:** this is the highest user-visible remote-development issue.

**Shared**
- Terminal/file DTOs and service contracts

**Backend**
- Terminal execution implementation
- Workspace file read/write/patch implementation
- Remote-safe VFS/project-root resolution

**Frontend**
- UI/tool adapters that call the shared contracts

### Slice 2 — Review action flow
**Frontend**
- `ReviewSelectedAction`
- any editor/UI request collection
- TODO: replace current frontend-local review placeholder adapter with backend/RPC-backed execution

**Shared**
- review request/response DTOs
- RPC contract for review

**Backend**
- selected-code review orchestration
- PSI/project context extraction
- OpenAI request orchestration if server-owned

### Slice 3 — Search/embedding/indexing
**Backend-heavy**
- embedding/index services
- vector store
- project file listeners that should run server-side

### Slice 4 — MCP and build/test integrations
**Backend-heavy**
- MCP startup/services
- Gradle/test/build tools

### Slice 5 — UI/settings cleanup
**Frontend**
- tool window
- settings configurable UI
- icons/resources
- durable user-owned settings persistence and startup sync ownership

**Backend**
- runtime-only settings consumption
- compatibility-state removal only after startup sync and migration safety are confirmed

## Proposed target ownership map

### `:shared`
- DTOs
- stable service contracts
- transport-neutral result/error models
- constants used by both sides

### `:frontend`
- actions
- tool window UI
- settings UI
- durable persistence of user-editable Quanta settings
- startup sync that pushes the persisted settings snapshot to the backend
- frontend adapters/callers
- notifications/presentation helpers

### `:backend`
- PSI/project/file logic
- VFS write/read services
- terminal/build/test execution
- MCP startup/runtime services
- embeddings/search/indexing services
- runtime-only settings snapshot consumed by backend services
- compatibility storage only when needed for migration, not as a second source of truth

## Safety rules for next slices
- Migrate one vertical slice at a time.
- Prefer adding a shared contract first, then backend implementation, then frontend usage.
- Keep existing legacy runtime behavior untouched until the new slice compiles and is packaged.
- Validate `buildPlugin` after each slice.
- TODO: if a package boundary still looks mixed after docs land, keep the current behavior and document the overlap before refactoring it.