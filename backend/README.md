# Backend module

The backend module owns server-side IntelliJ Platform logic for Quanta AI.

## Responsibilities
- Project and PSI analysis
- RPC handling and backend services
- OpenAI orchestration and request execution
- File, terminal, embedding, and indexing implementations
- Backend settings, listeners, and persistence
- ACP agent discovery and read-only delegation: known PATH candidates and user-configured applications/TCP endpoints are verified with a bounded ACP `initialize` handshake, then can receive a bounded ACP session task from the main AI

## ACP configuration

In **Settings → Tools → Quanta AI**, use **Configure ACP Agents…** to add local applications or TCP endpoints. The structured editor requires a name and exactly one transport:

- **Local application:** an executable path or a single command launched directly without a shell.
- **TCP endpoint:** a hostname/IP address and a port from `1` through `65535`. TCP uses newline-delimited ACP JSON-RPC.

Duplicate application targets and duplicate TCP host/port pairs are rejected. Configured targets are still returned only after a successful bounded `initialize` handshake.

## ACP delegation

The main AI can call `DiscoverAcpAgentsTool`, then use `DelegateToAcpAgentTool` with a returned agent ID and a focused task. Delegation creates a fresh stdio or TCP connection, completes `initialize`, creates an ACP session, sends `session/prompt`, collects streamed `session/update` text, and closes the session transport after the final response.

The initial integration is deliberately bounded and advisory: it adds a read-only instruction to every delegated task and returns the external agent's findings for the main AI to verify. It does not expose ACP agent edits, permissions, credentials, or a reusable interactive session yet. Transport failures and cleanup are handled automatically; the request timeout is configurable from 1 to 120 seconds per tool call.

## Key packages
- `project/` — project and PSI helpers
- `tools/` — backend agent/tool implementations
- `services/` — domain services and orchestrators
- `rpc/` — backend RPC endpoints
- `contracts/` — backend-side contract helpers

## Documentation strategy
This module follows the bottom-up documentation rule described in `AGENTS.md`.
Prefer KDoc on backend APIs and add package-level docs only where package ownership or module boundaries are otherwise unclear. This file remains the module entry point.