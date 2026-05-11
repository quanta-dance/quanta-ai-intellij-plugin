# frontend/rpc

Frontend RPC clients, bridges, and request helpers.

## Owns
- RPC client usage from the UI side
- Bridge code that invokes backend services
- Transport helpers for frontend callers

## Notes
Keep protocol definitions in `shared`; keep presentation logic out of this package.
