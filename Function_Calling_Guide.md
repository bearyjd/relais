# Function Calling with Relais

Relais serves an **OpenAI-compatible** API, so function/tool calling works the way it does against any
OpenAI `/v1/chat/completions` endpoint — point your existing client at the node. There are two modes:

1. **Client-side tools** (standard OpenAI) — you supply `tools[]`; the model emits `tool_calls`; **you**
   execute them and send the results back.
2. **Node-side built-in tools** (`node_tools: true`) — the node executes a small curated set of safe,
   deterministic built-ins **itself** (single hop), and returns the grounded answer directly.

The driving model is an on-device Gemma-4-class model (~2–4B effective). It is reliable at a **single**
tool call, not at long autonomous agent loops — design for one round of grounding.

## 1. Client-side tools (you execute)

Standard OpenAI shape. The model decides when to call; the node returns `tool_calls`; you run them and
append a `role: "tool"` message, then call again for the final answer.

```bash
curl -k https://<node-ip>:8443/v1/chat/completions \
  -H "Authorization: Bearer $RELAIS_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemma-4-E4B-it",
    "messages": [{"role": "user", "content": "What is the weather in Paris?"}],
    "tools": [{
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "Get current weather for a city",
        "parameters": {
          "type": "object",
          "properties": {"city": {"type": "string"}},
          "required": ["city"]
        }
      }
    }]
  }'
```

Notes:
- Tool-calling uses the **native** litertlm tool API (not prompt scraping).
- The model's typed-argument quirk (`{"type":"STRING","value":"…"}`) is unwrapped for you.

## 2. Node-side built-in tools (`node_tools`)

Opt in with `node_tools: true` (alias `x_relais_node_tools`). The node advertises its built-ins, runs
the one the model calls, and re-generates **once** with the result folded in (tools suppressed on the
second pass) → a final text answer, no `tool_calls` for you to handle.

| tool | arguments | does |
|---|---|---|
| `rag_search` | `query`, `top_k?` | search the ingested RAG corpus |
| `calculator` | `expression` | arithmetic via a hand-rolled parser (no `eval`/exec) |
| `current_datetime` | — | device local date-time (ISO-8601) |
| `unit_convert` | `value`, `from`, `to` | length / mass / temperature |

```bash
curl -k https://<node-ip>:8443/v1/chat/completions \
  -H "Authorization: Bearer $RELAIS_API_KEY" -H "Content-Type: application/json" \
  -d '{"model":"gemma-4-E4B-it",
       "messages":[{"role":"user","content":"What is 17.5% of 240?"}],
       "node_tools":true}'
```

- Built-ins and your `tools[]` **coexist**; a built-in is a single hop the node runs, a client tool flows
  back to you as `tool_calls`. **Your tool name wins on a collision** (supply your own `calculator` and
  the node won't shadow it).
- Tool/RAG output is fenced as untrusted **DATA** after your system prompt — a poisoned passage can't
  override your guardrails.
- **Cost:** a triggered built-in runs **two** generations (~2× latency) and holds one admission slot.

See [`docs/node-tools-api.md`](docs/node-tools-api.md) for the full contract.

## Related: structured output

For a guaranteed-shape JSON answer (instead of a function call), use `response_format` with a
`json_schema` or `json_object` — the node validates and repairs the output. Note `node_tools`/`tools`
takes precedence: a `response_format` on the **same** request is ignored.

## Extending the on-device app's own actions

The bundled Gallery UI also has an in-app "mobile actions" custom task (camera/intent/etc.) — that is
separate from the node's HTTP tool-calling above and is not part of the node's API surface.
