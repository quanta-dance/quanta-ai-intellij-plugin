# Backend module

The backend module owns server-side IntelliJ Platform logic for Quanta AI.

## Responsibilities
- Project and PSI analysis
- RPC handling and backend services
- OpenAI orchestration and request execution
- File, terminal, embedding, and indexing implementations
- Backend settings, listeners, and persistence

## Key packages
- `project/` — project and PSI helpers
- `tools/` — backend agent/tool implementations
- `services/` — domain services and orchestrators
- `rpc/` — backend RPC endpoints
- `contracts/` — backend-side contract helpers

## Documentation strategy
This module follows the bottom-up documentation rule described in `AGENTS.md`.
Deep package docs live beside their package and should be kept short, with this file serving as the module entry point.
