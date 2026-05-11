# backend/services

Backend services that orchestrate analysis, persistence, tools, and external integrations.

## Owns
- Long-lived backend services
- Orchestration across tools, repositories, and models
- Business logic that does not belong in a tool or repository

## Notes
Split new functionality here only when it clearly needs coordination across multiple lower-level packages.
