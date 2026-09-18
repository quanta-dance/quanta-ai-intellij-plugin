# Backend module

The backend module owns server-side IntelliJ Platform logic for Quanta AI.

## Responsibilities
- Project and PSI analysis
- RPC handling and backend services
- OpenAI orchestration and request execution
- File, terminal, embedding, and indexing implementations
- Backend settings, listeners, and persistence
- ACP agent discovery and live collaboration: known PATH candidates and user-configured applications/TCP endpoints are verified with a bounded ACP `initialize` handshake, then can join the main AI as independent, persistent-session teammates

## ACP configuration

In **Settings → Tools → Quanta AI**, use **Configure ACP Agents…** to add local applications or TCP endpoints. The structured editor requires a name and exactly one transport:

- **Local application:** an executable path or a single command launched directly without a shell.
- **TCP endpoint:** a hostname/IP address and a port from `1` through `65535`. TCP uses newline-delimited ACP JSON-RPC.

Duplicate application targets and duplicate TCP host/port pairs are rejected. Configured targets are still returned only after a successful bounded `initialize` handshake.

## ACP delegation

Discovery is **availability only**. It starts a bounded probe, verifies `initialize`, and closes the probe connection; it never authorizes an ACP agent for work. In agentic mode, the user opens the **Agentic team** roster, refreshes discovery, and explicitly adds an external agent to the current chat. That chat-scoped allowlist is persisted with the chat session. It can be revoked at any time; revocation cancels active work for that agent and closes its live ACP transport.

The main AI calls `DiscoverAcpAgentsTool` to inspect availability, then may use `DelegateToAcpAgentTool` only with an agent the user enabled for the current chat. The backend enforces both agentic-mode activation and the chat allowlist; discovery alone never grants a delegation capability. The delegate tool queues work and returns a `delegationId` immediately, so the main agent and internal team can continue independent work. `SendAcpDelegationMessageTool` sends focused follow-ups to that delegation's retained ACP session; `GetAcpDelegationStatusTool` reads its current state or final findings; `CancelAcpDelegationTool` closes the live transport and stops work.

The background worker creates a fresh stdio or TCP connection, completes `initialize`, creates an ACP session, and sends `session/prompt`. After a prompt returns, the transport and session remain available for serialized follow-up prompts until cancellation, a transport failure, project disposal, or future idle cleanup. Delegations are session-scoped in memory and capped at two concurrent active prompts per project.

Chat displays one stable task card only while the task's originating chat session remains active. The card updates in place for running, completed, failed, cancelled, **Needs sign-in**, **Needs approval**, and **Needs input** states, so background events cannot reorder the normal conversation. The card explicitly identifies an **Independent external ACP agent** rather than pretending Quanta controls its tools. Curated material findings, blockers, failures, and completion events enter the main-agent coordination inbox; routine progress stays on the card. For sign-in, Quanta shows the vendor-neutral instruction “Complete the external agent's sign-in, then return here”; it never receives or stores the external agent's credentials. Interactive ACP requests are detected and reported, but are rejected at the protocol boundary until an explicit permission/input bridge is implemented. Transport failures and cleanup are handled automatically; the timeout is configurable from 1 to 120 seconds per task.

## Key packages
- `project/` — project and PSI helpers
- `tools/` — backend agent/tool implementations
- `services/` — domain services and orchestrators
- `rpc/` — backend RPC endpoints
- `contracts/` — backend-side contract helpers

## Documentation strategy
This module follows the bottom-up documentation rule described in `AGENTS.md`.
Prefer KDoc on backend APIs and add package-level docs only where package ownership or module boundaries are otherwise unclear. This file remains the module entry point.