# backend/repository

Persistence adapters and repository-style access for backend-owned data.

## Owns
- Storage access
- CRUD helpers for backend state
- Data-mapping code between storage and domain models

## Notes
Keep orchestration out of this package; it should remain storage-focused.
