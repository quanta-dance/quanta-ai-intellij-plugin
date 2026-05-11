# backend/settings

Configuration and persisted-state helpers for backend-side settings, defaults, and synchronization.

## Owns
- Backend settings models and state
- Settings migration or normalization helpers
- Backend-facing configuration accessors

## Notes
If a setting is consumed directly by the frontend UI, keep the source of truth here and expose it through a shared contract when needed.
