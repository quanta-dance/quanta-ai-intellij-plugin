# Shared module

The shared module contains transport-neutral contracts, serializers, and shared helpers used by both frontend and backend.

## Responsibilities
- Shared DTOs and RPC contracts
- Serialization helpers
- Cross-module constants and marker interfaces
- API shapes that must remain stable between frontend and backend

## Key packages
- `contracts/` — cross-module data and request/response shapes
- `rpc/` — shared RPC APIs
- `tools/` — shared tool-facing abstractions

## Documentation strategy
Keep this module focused on stable API surfaces. Prefer KDoc on contracts and RPC APIs; add package-level docs only when a cross-module boundary needs explicit explanation. If a contract looks module-specific, move the implementation to the owning module and leave a TODO if the split is still in progress.