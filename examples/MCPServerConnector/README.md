# MCP Server Connector for IBM VDI 10

A custom IBM Verify Directory Integrator (VDI 10) **Server-mode connector** that turns an AssemblyLine into an **MCP (Model Context Protocol) server** over Streamable HTTP. When the AL runs, it *is* an MCP server: an inbound `tools/call` becomes the AL's work Entry, and the AL's reply Entry becomes the tool result. Validated end-to-end with **Claude Code as a live MCP client**.

## How it works

- Subclasses the stock `HTTPServerConnector` to reuse its socket/TLS/HTTP framing, adding a JSON-RPC 2.0 / MCP message layer on top.
- `initialize`, `notifications/initialized`, and `tools/list` are answered directly by the connector. `tools/call` is mapped to a work Entry (`$mcp.tool`, `$mcp.arguments`, flattened scalar args, …); the AL's Data Flow does the work and sets `$mcp.result`/`$mcp.structured`/`$mcp.isError`, which the connector translates back into the MCP tool result.
- Stateless JSON responses (no SSE/sessions in v1). Transport security: bearer token, optional mTLS, `Origin` allow-list, method/path/protocol-version enforcement.

## Documentation

- **[docs/SPEC.md](docs/SPEC.md)** — full design, locked decisions, phased plan, and the narrative of every phase including the bugs found and fixed live.
- **[docs/CONFIGURE.md](docs/CONFIGURE.md)** — step-by-step setup: connector config, the three attribute contracts, security, connecting Claude, troubleshooting.
- **[docs/GOTCHAS.md](docs/GOTCHAS.md)** — condensed index of the non-obvious VDI platform behaviors that shaped this connector. **Read this first if you're extending or porting it.**
- **[docs/BACKLOG.md](docs/BACKLOG.md)** — what's done in the v1 tail, what's deferred, and design notes for v2 (SSE, sessions).

## Build

Requires a local VDI 10 install (its `jars/common`, `jars/connectors`, and `jars/3rdparty` jars are used compile-only — nothing is repackaged).

```
mvn -Dvdi.home=/path/to/ISVDI package
```

`vdi.home` defaults to the `VDI_HOME` environment variable if `-Dvdi.home` is omitted.

## Test

```
mvn -Dvdi.home=/path/to/ISVDI test
```

28 JUnit 5 tests cover the JSON-RPC/MCP layer and transport gates (initialize/version negotiation, tools/list, tools/call work-Entry mapping, reserved `$mcp.*` argument protection, actor header, reply mapping and no-leak behavior, bearer/Origin/path/method/size/protocol-version checks, health probe). They run without a socket or running VDI; the platform jars are needed on the test classpath only (see `pom.xml`).

## Deploy

Copy the built jar (`target/mcp-server-connector.jar`) to `VDI_install_dir/jars/connectors/`, restart the Config Editor / server, and the `MCPServerConnector` connector appears under Connectors. See [docs/CONFIGURE.md](docs/CONFIGURE.md) to build the AssemblyLine and connect a client.

## Quick connect (Claude Code)

```
claude mcp add --transport http vdi-mcp http://127.0.0.1:8443/mcp \
  --header "Authorization: Bearer <your-token>"
```

Start a fresh Claude Code session (tools register at session start), then ask it to use one of your configured tools.

## License

[Apache-2.0](../../LICENSE) — provided "as is", without warranty of any kind. IBM Security Directory Integrator and IBM Verify Identity Governance are trademarks of IBM; this is an independent connector, not an IBM product.
