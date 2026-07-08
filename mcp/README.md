# Model Context Protocol (MCP) in Relais

## Relais-specific notes (read this first)

This directory's walkthrough (below) is **upstream `google-ai-edge/gallery` content**, kept
because it's still accurate end-user documentation for adding a local or cloud MCP server. This
section is Relais-specific: what MCP means in this fork, where it lives in code, and how it
differs from the node's own HTTP tool-calling surface.

**MCP here is an in-app Agent Chat client feature, not the node's HTTP API.** Relais serves an
OpenAI-compatible API (`/v1/chat/completions`) with its own tool-calling surfaces — client-side
`tools[]` and node-side `node_tools` built-ins (`rag_search`, `calculator`, `current_datetime`,
`unit_convert`) — documented in [`Function_Calling_Guide.md`](../Function_Calling_Guide.md). MCP
is unrelated to that HTTP surface: it only powers the **Agent Chat** screen inside the app, where
the on-device model itself acts as an MCP *client* connecting out to remote MCP *servers* the user
configures (e.g. `fetch`, Maps Grounding Lite). The two systems don't share code or state.

**Where it lives** (all under
`Android/src/app/src/main/java/cc/grepon/relais/customtasks/agentchat/`):

| File | Role |
|---|---|
| `McpManagerViewModel.kt` | Owns the MCP client state: connects (`StreamableHttpClientTransport` from the [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)), discovers tools via `listTools()`, persists servers to a proto `DataStore`, and builds the tools prompt injected into the model (`getToolsPrompt()`). |
| `McpServersSerializer.kt` | The `DataStore<McpServers>` `Serializer` — reads/writes the persisted server list as a protobuf, matching `mcp.proto` below. |
| `McpToolCallPermissionDialog.kt` | The per-invocation permission prompt (`AlertDialog`) shown when the model wants to call a tool: **Always Allow** / **Allow Once** / **Don't Allow** (`PermissionResult`). "Always Allow" persists via `McpManagerViewModel.setMcpToolAlwaysAllow`. |
| `McpToolManagerBottomSheet.kt` | Per-server tool list UI — toggle individual tools/servers on or off. |
| `McpManagerBottomSheet.kt` | The "Manage MCP servers" screen reachable from the **MCP** button under the Agent Chat input field (add/remove servers, see connection status/errors). |
| `AddMcpServerFromUrlDialog.kt` | The "Add MCP Server" form — URL + optional auth header (`McpAuth`: none / request header / OAuth-reserved). |
| `AddMcpDisclaimerDialog.kt` | One-time disclaimer shown before a user adds their first MCP server (data leaves the device to a third-party server). |

The persisted schema is `Android/src/app/src/main/proto/mcp.proto`: `McpServers` (a list of
`McpServer`), each `McpServer` has a `url`, `enabled` flag, and a list of `McpTool` (`name`,
`description`, `input_schema` as a JSON string, `enabled`, `always_allow`). `AgentChatScreen.kt` is
the call site — it owns the `McpManagerViewModel` instance (via `hiltViewModel()`), renders
`McpToolCallPermissionDialog` when a tool call needs a decision, and folds
`mcpManagerViewModel.getToolsPrompt()` into the model's context.

**Permission model:** every tool call from a server without an "always allow" flag set surfaces
`McpToolCallPermissionDialog` before it runs. The user's choice maps to `PermissionResult`
(`ALWAYS_ALLOW` / `ALLOW_ONCE` / `DENY`); `ALWAYS_ALLOW` is persisted per tool
(`setMcpToolAlwaysAllow`) so future calls to that exact tool skip the prompt.

**Status:** MCP integration is experimental in this fork, same as upstream (see the `[!IMPORTANT]`
note below) — the walkthrough content is unmodified upstream Gallery documentation.

---

## Model Context Protocol (MCP) in Google AI Edge Gallery

*(Upstream `google-ai-edge/gallery` documentation — general MCP walkthrough, not Relais-specific.
Still accurate for adding a local or cloud MCP server to the app.)*

## Overview

Google AI Edge Gallery leverages on-device machine learning models to deliver low-latency, privacy-preserving inference. However, standalone on-device models inherently lack access to real-time data, web services, and dynamic action execution. To solve this limitation, Google AI Edge Gallery integrates the [**Model Context Protocol (MCP)**](https://modelcontextprotocol.io/docs/getting-started/intro), an open standard establishing secure, universal communication between AI models and external systems. By adopting this standardized client-server architecture, the app decouples its on-device models (the client) from external tools and data sources (the servers), creating a single unified interface for dynamic context retrieval and tool execution. 

* **Dynamic Tool Discovery:** The app connects to configured MCP servers, dynamically loading and parsing JSON schemas for available tools.
* **Contextual Injection:** Discovered tool descriptions and schemas are directly injected into the on-device model's prompt context.
* **Secure Agentic Workflows:** When the on-device model decides to call a tool, the application routes the execution directly to the corresponding MCP server. Granular user permission controls, such as per-invocation prompts and toggleable "always allow" rules, ensure that remote actions remain fully transparent and secure.

> [!IMPORTANT]
> MCP integration is currently experimental.

## Add a Local MCP Server

In this section, we will walk through adding one of the official example MCP servers, [`fetch`](https://github.com/modelcontextprotocol/servers/tree/main/src/fetch), to Google AI Edge Gallery.

### Step 1: Start the Server in StreamableHTTP Mode

Most open-source MCP servers are built exclusively with the **stdio** transport (learn more about [MCP transport types](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)), under the assumption that the MCP server and the LLM client (e.g., Gemini CLI, Claude Code, etc) run on the same local machine. However, this does not work natively for a mobile application like Google AI Edge Gallery.

To make the server accessible to Google AI Edge Gallery over the network, it needs to run in **StreamableHTTP** mode. Since the `fetch` example only supports `stdio` out of the box, we can use an adapter tool called [`supergateway`](https://github.com/supercorp-ai/supergateway) to convert the `stdio` transport to `StreamableHTTP` without rewriting the server code.

Run the following commands in your terminal to set up and launch the server. Make sure `python` and `node.js` have been installed:

```bash
# Install the `fetch` MCP server.
$ python3 -m venv venv
$ source venv/bin/activate
$ pip install mcp-server-fetch

# Start Supergateway.
$ npx -y supergateway --stdio 'mcp-server-fetch' --outputTransport streamableHttp
```

Now, the server is listening at `http://localhost:8000/mcp`.

### Step 2: Expose the Server via a Public URL

Google AI Edge Gallery requires the local server to have a publicly routable URL to access it. If your host machine is already serving behind an HTTPS DNS address, you can skip this step.

Otherwise, you can expose the local port using a free tool like [Cloudflare Quick Tunnels](https://try.cloudflare.com/). First, install the `cloudflared` command-line tool on your machine, then run the following command in a separate terminal window:

```bash
# Ensure the target port matches the Supergateway service started in Step 1.
$ cloudflared tunnel --url http://localhost:8000
```

The command will output a unique public HTTPS URL (e.g., `https://<random-string>.trycloudflare.com`). When entering this address in the app, be sure to append the `/mcp` endpoint path.

### Step 3: Add MCP server URL

1. Open **Agent Chat** in the app, select a model (we recommend **Gemma-4-E4B** for better model quality), and navigate to the **Manage MCP servers** screen by clicking the **MCP** button below the input text area.

    <img width="320" alt="mcp_entry" src="screenshots/mcp_entry.png" />

2. Click **Add MCP Server** and enter your server URL. Make sure to append the `/mcp` endpoint path:

    <img width="320" alt="add_server_url" src="screenshots/add_server_url.png" />

3. Once connected, the app will automatically detect the tools provided by the MCP server, and you can also toggle servers and tools.

    <img width="320" alt="mcp_tools_management" src="screenshots/mcp_tools_management.png" />

### Step 4: Try a prompt

- Fetch https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery and summarize the app's features.
- Summarize the latest news from CNN.

> [!WARNING]
> Please check out the [official documentation](https://github.com/modelcontextprotocol/servers/blob/main/src/fetch/README.md) of the `fetch` MCP server and be aware of its limitations.

## Add a Cloud MCP Server

Unlike local development setups, cloud-hosted MCP servers are fully managed, run directly on external cloud infrastructure, and operate in **StreamableHTTP** mode by default. Because these services are exposed publicly, they require explicit authorization to control access.

Google AI Edge Gallery supports remote server authentication by allowing you to inject custom keys and credentials directly into the HTTP request headers. (We are working on supporting the full OAuth flow)

The following steps demonstrate how to connect the official **Maps Grounding Lite** cloud server ([https://mapstools.googleapis.com/mcp](https://mapstools.googleapis.com/mcp)) to the app. This service provides your on-device model with tools to query live geographical locations, weather conditions, and travel routes.

### Step 1: Set Up Credentials in Google Cloud

Before configuring the app, you need to set up a project and generate a valid credential to authorize requests to the Maps Grounding Lite API. Follow the steps on the [official site](https://developers.google.com/maps/ai/grounding-lite).

### Step 2: Add the Cloud Server with Custom Headers

1. Open **Agent Chat** in the app, tap the **MCP** button under the message input field, and select **Add MCP Server**.
2. Fill in the server details, making sure to provide the cloud endpoint and the mandatory authentication header required by Google Cloud services:
    * **Server URL:** `https://mapstools.googleapis.com/mcp`.
    * **Header name:** `X-Goog-Api-Key`
    * **Header value:** The API key you generated in Step 1.

    <img width="320" alt="mcp_auth" src="screenshots/mcp_auth.png" />

### Step 3: Try a Prompt

- **compute_routes**

  Calculate the route from San Francisco to San Jose.

- **search_places**

  Recommend some highly rated Ramen places in downtown Mountain View CA.

> [!WARNING]
> Due to model limitations, you might need to enable only the specific tool shown above the prompt and disable others for them to work. See the Limitations section below for more details.

## Limitations

While integrating MCP servers significantly expands the capabilities of on-device AI, users should keep the following constraints in mind during the experimental phase:

* **Model Compatibility:** Due to the complexity and length of the injected system prompts required for tool use, **Gemma-4-E4B** currently offers the most stable and reliable tool-calling behavior. Smaller models may struggle to parse schemas accurately.
* **Context Window Constraints:** Most official MCP servers are designed for desktop applications with massive context windows (e.g., 32k to 200k+ tokens). Google AI Edge Gallery models operate within a tighter context limit (**4k to 10k tokens**). Because large tool descriptions or JSON schemas can quickly consume the majority of your context window, it is highly recommended to **only enable the specific tools you need** for your task.
* **GPU Acceleration and Numerical Accuracy:** The app utilizes GPU hardware acceleration to ensure low-latency performance. However, some mobile GPU kernels do not handle floating-point or high-precision math with the same consistency as a CPU. As a result, the model may occasionally output incorrect or slightly distorted numbers when executing math-heavy or coordinate-based tools.
