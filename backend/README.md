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

The main AI calls `DiscoverAcpAgentsTool`, then uses `DelegateToAcpAgentTool` with a returned agent ID and a focused task. The delegate tool queues work and returns a `delegationId` immediately, so the main agent and internal team can continue independent work. `GetAcpDelegationStatusTool` reads the current state or final findings; `CancelAcpDelegationTool` stops queued/running work.

The background worker creates a fresh stdio or TCP connection, completes `initialize`, creates an ACP session, sends `session/prompt`, collects streamed `session/update` text, and closes the transport after final completion, cancellation, or failure. Delegations are session-scoped in memory, capped at two concurrent tasks per project, and cancelled when the project closes.

Chat displays one stable task card only while the task's originating chat session remains active. The card updates in place for running, completed, failed, cancelled, **Needs sign-in**, **Needs approval**, and **Needs input** states, so background events cannot reorder the normal conversation. For sign-in, Quanta shows the vendor-neutral instruction “Complete the external agent's sign-in, then return here”; it never receives or stores the external agent's credentials. Interactive ACP requests are detected and reported, but are rejected at the protocol boundary until an explicit permission/input bridge is implemented. Completion is deliberately informational rather than an automatic new main-agent turn, so it cannot interrupt or overwrite a newer user request. The initial integration remains bounded and advisory: every task has a read-only instruction; ACP edits and reusable multi-turn sessions are not exposed yet. Transport failures and cleanup are handled automatically; the timeout is configurable from 1 to 120 seconds per task.

## Key packages
- `project/` — project and PSI helpers
- `tools/` — backend agent/tool implementations
- `services/` — domain services and orchestrators
- `rpc/` — backend RPC endpoints
- `contracts/` — backend-side contract helpers

## Documentation strategy
This module follows the bottom-up documentation rule described in `AGENTS.md`.
Prefer KDoc on backend APIs and add package-level docs only where package ownership or module boundaries are otherwise unclear. This file remains the module entry point.