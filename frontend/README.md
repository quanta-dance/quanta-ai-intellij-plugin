# Frontend module

The frontend module owns IntelliJ UI, actions, and local adapters for Quanta AI.

## Responsibilities
- Tool windows, actions, and editor UI
- Settings and configuration UI
- Frontend adapters for shared/backend contracts
- Voice, review, comment, prompt, and sound UX surfaces
- UI state, coroutines, and presentation helpers

## Key packages
- `actions/` — user-facing IDE actions
- `toolwindow/` — tool window entry points
- `settings/` — settings UI and persistence helpers
- `services/` — frontend services and adapters
- `rpc/` — frontend RPC clients and bridges

## Documentation strategy
This module is documented bottom-up. Prefer KDoc on frontend UI/services first, and add package-level docs only when local ownership or UI/backend boundaries need clarification.