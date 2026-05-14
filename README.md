[![Download from JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Quanta%20AI-blue?logo=jetbrains)](https://plugins.jetbrains.com/plugin/29113-quanta-ai/)

# Quanta AI

Quanta AI is an AI-powered coding assistant designed to integrate with IntelliJ IDEA. This plugin leverages OpenAI
models to assist developers with code modifications, reviews, and refactoring directly within the IDE.

## Features

- **AI-Powered Code Assistance:**
    - Code Review: Get AI suggestions on selected code
    - Code Refactoring: Automatically refactor code with AI
    - Code Commenting: Generate AI-enhanced documentation
    - Custom Prompts: Execute user-defined AI prompts

- **Agentic Mode:** Enable AI agents to autonomously perform complex development tasks

- **Voice Interaction:** Speak to AI via microphone and receive voice feedback (with optional local TTS)

- **IDE Tool Integration:**
    - File operations (read, create, update, delete)
    - Dependency inspection and management
    - File reference tracking
    - Go language testing support
    - Custom terminal command execution (with security allowlist)

- **Advanced Features:**
    - MCP (Model Context Protocol) support for tool integration
    - Session scheduling for automated tasks
    - Tool catalog with customizable scopes
    - Dynamic model switching (start with GPT-5-MINI, upgrade as needed)
    - Embeddings and vector search via local SQLite store

## Documentation

- Module README files live in `backend/`, `frontend/`, and `shared/`.
- Repo-level migration and ownership notes live in `docs/`, especially `docs/architecture-overview.md` and `docs/modular-migration-map.md`. The architecture overview also explains the current frontend-persisted/backend-runtime settings ownership model.
- Important behavioral questions should be answered by tests, integration scenarios, or other executable verification before relying on prose.
- Code-level API documentation should use Kotlin KDoc.
- Package-level documentation should be added only where boundaries need explanation, using package-level KDoc or a small dedicated package doc file.
- `AGENTS.md` explains the discovery order and maintenance rules for future sessions.

## Prerequisites

- IntelliJ IDEA
- An API Key from OpenAI. Generate one at: [OpenAI API Keys](https://platform.openai.com/api-keys)
- Your OpenAI account must have a positive balance

## Installation

1. Install plugin from [Marketplace](https://plugins.jetbrains.com/plugin/29113-quanta-ai) or
   from [Releases](https://github.com/quanta-dance/quanta-ai-intellij-plugin/releases).
2. Open the project in IntelliJ IDEA.
3. Configure your OpenAI API Key in the plugin settings.

## Usage

### Basic Features

- Access Quanta AI features through the Editor Popup Menu or Floating Code Toolbar
- Use voice commands if voice interaction is enabled in the settings

### Agentic Mode

- Enable Agentic Mode in settings to let AI agents autonomously handle multi-step tasks
- Configure maximum automatic turns (1-100) to control agent behavior
- Stop running agents anytime with the Stop All Agents button

### Model Configuration

- Select a GPT-5 model tier in settings (NANO, MINI, standard, CODEX, or 4)
- Enable Dynamic Model Switching to start with MINI and upgrade based on task complexity
- Set maximum token output (default: 2048)

### Security Settings

- Enable Terminal Tool only if needed for shell command execution
- Configure allowed command prefixes via comma-separated CSV
- Debug Mode available for development troubleshooting

## Best Practices

- Regularly update your API Key and avoid sharing it publicly.
- Utilize the voice feature in a quiet environment for accurate transcription.

## Support

- For support, contact support@quanta-dance.com or open
  an [issue](https://github.com/quanta-dance/quanta-ai-intellij-plugin/issues).

## Contribution

We welcome contributions via issues.
All pull requests require signing a Contributor License Agreement (CLA) before merging.

## License

SPDX: GPL-3.0-only

This project is licensed under the GNU General Public License v3.0. See the LICENSE file in the repository root for the
full license text.

NOTICE: This project includes third-party components that may be subject to additional licenses. See the NOTICE.txt file
for attribution and license notes.
