# backend/tools

Backend tool implementations exposed to agent workflows.

## Owns
- File operations
- Search, indexing, and embeddings tools
- Build, test, and environment tools
- MCP-backed backend capabilities

## Notes
Tool classes should stay thin and delegate to focused services where possible.
