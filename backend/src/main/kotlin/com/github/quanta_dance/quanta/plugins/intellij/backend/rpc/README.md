# backend/rpc

Backend RPC endpoints and server-side transport glue.

## Owns
- RPC service implementations
- Request dispatch and response shaping
- Backend exposure of shared APIs

## Notes
Keep protocol definitions in `shared`; keep execution and lifecycle in this package.
