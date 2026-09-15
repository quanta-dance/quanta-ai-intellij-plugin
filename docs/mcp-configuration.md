# MCP Server Configuration

Quanta AI loads MCP server definitions from a JSON file on the frontend machine and synchronizes the effective
configuration to the backend. The file is stored outside the project so it can be shared by IDE projects on the same
machine.

## File location

Open **Settings | Tools | Quanta AI | MCP Servers** and use the MCP configuration action to create or edit the file.

The file path is based on the JetBrains common data directory. On macOS, it is normally:

```text
~/Library/Application Support/JetBrains/QuantaDance/.quantadance/mcp-servers.json
```

The frontend log prints the exact path it is using at startup. Use that path if the IDE installation has a custom data
directory.

## Format

The file contains a named `mcpServers` object. Each server must define either a local `command` or a remote `url`.

```json
{
  "mcpServers": {
    "server-name": {
      "command": "..."
    }
  }
}
```

The `mcpServers` naming convention is widely used by MCP clients, but the MCP protocol does not define a universal
local JSON configuration schema. Server compatibility is guaranteed by the MCP transport and authorization protocol,
not by this file format.

## Local stdio servers

Use `command`, optional `args`, and optional `env` to run a local MCP server over standard input/output.

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/path/to/allowed/root"
      ],
      "env": {
        "NODE_OPTIONS": "--no-deprecation"
      }
    }
  }
}
```

Only configure paths and environment values that you trust. A local MCP server runs as your user and can access the
resources permitted by its command and configuration.

## Remote Streamable HTTP servers

Use `url` for a remote HTTP(S) MCP server. Quanta AI treats remote URLs as MCP Streamable HTTP endpoints. Do not add a
client-specific `transport`, `auth`, or `oauth` field.

```json
{
  "mcpServers": {
    "remote-service": {
      "url": "https://mcp.example.com/team/service/mcp",
      "headers": {
        "X-Tenant-Id": "example-tenant",
        "X-Route": "production"
      }
    }
  }
}
```

`headers` is for required non-secret request headers, such as tenant or routing identifiers. The configured URL must
accept MCP JSON-RPC `POST` requests, including the protocol `initialize` request.

Do not use an in-cluster Kubernetes service address from an IDE running outside that cluster. Use the external endpoint
documented for native clients.

## OAuth authorization for remote servers

OAuth is discovered from the remote MCP server, not enabled by a JSON flag:

1. Quanta AI sends an unauthenticated MCP `initialize` request to the configured URL, retaining configured non-secret
   headers.
2. If the server responds with `401 Unauthorized` and a `WWW-Authenticate: Bearer` challenge containing
   `resource_metadata`, Quanta AI fetches the advertised OAuth protected-resource metadata.
3. Quanta AI discovers the authorization server, opens the system browser, and completes OAuth Authorization Code with
   PKCE through a localhost callback.
4. The browser completion page identifies the configured MCP server. Return to IntelliJ while it exchanges the code and
   establishes the MCP connection.
5. Access and refresh tokens are stored in IntelliJ Password Safe, not in this JSON file.
6. Later connections reuse a valid access token or refresh it when the authorization server supports refresh tokens.

For example, an OAuth-protected server needs no OAuth-specific JSON settings:

```json
{
  "mcpServers": {
    "remote-server": {
      "url": "https://mcp.example.com/mcp"
    }
  }
}
```

Do not add `Authorization: Bearer ...`, access tokens, refresh tokens, authorization codes, passwords, or client secrets
to this file. If a server requires a pre-registered OAuth public client and does not support dynamic client registration,
its provider may give you a non-secret public client ID. Configure it as `oauthClientId`:

```json
{
  "mcpServers": {
    "private-server": {
      "url": "https://mcp.example.com/mcp",
      "oauthClientId": "public-client-id-provided-by-the-server"
    }
  }
}
```

A public client ID is not a secret. Never configure a client secret in the plugin.

## Static authentication

Some remote servers use an API key or a manually managed bearer token instead of MCP OAuth. Put such credentials in
IntelliJ Password Safe or another approved secret-management mechanism whenever possible. If a server requires a static
HTTP header, it can be supplied through `headers`:

```json
{
  "mcpServers": {
    "internal-api": {
      "url": "https://mcp.example.com/mcp",
      "headers": {
        "X-API-Key": "<secret>"
      }
    }
  }
}
```

Treat this file as sensitive if it contains a secret. Do not commit it, include it in screenshots, or paste it into logs
or chat. A configured `Authorization` header takes precedence over automatic OAuth handling.

## Troubleshooting

- Check **Help | Show Log in Finder/Explorer** and search for `McpClientService` or `McpOAuthService`.
- The frontend startup log identifies the exact configuration file path being synchronized.
- `connected to MCP server` followed by `discovered ... tool(s)` confirms a usable connection.
- A `401` response with a Bearer challenge should trigger browser authorization once. If authorization is cancelled or
  fails, automatic retries are paused to avoid repeated browser popups; change the server configuration or restart the
  IDE before retrying.
- A `404` during MCP initialization means the configured external URL does not serve the expected MCP endpoint. Browser
  authorization cannot fix an incorrect route; confirm the exact public endpoint with the server owner.
- If OAuth discovery fails, verify that the MCP endpoint returns the required `WWW-Authenticate` Bearer challenge and
  that the advertised protected-resource metadata URL is publicly reachable.
- If a server uses headers for routing, use the exact non-secret header names and values documented by its owner.

## Configuration changes

Save the JSON file after editing. Quanta AI watches it and synchronizes changes to the backend. Existing connections are
reconciled against the updated configuration and tools are rediscovered in the background.
