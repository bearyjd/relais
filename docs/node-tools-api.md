# Node-side tools (`node_tools` on `/v1/chat/completions`)

A **default-off, opt-in** mode where the node executes a small curated set of **safe built-in tools
itself** — a single hop — instead of only emitting the tool call for the client to run.

```json
{ "model": "…", "messages": [...], "node_tools": true }
```

(`x_relais_node_tools` is the namespaced alias.)

## Why single-hop, and why only these tools

The driving model is an on-device Gemma-4-class model (~2–4B effective params). It's competent at a
**single** tool call but unreliable at autonomous multi-step agent loops, so this is deliberately
**one round of grounding, not an agent**: the node advertises the built-ins, the model calls one (or
more), the node runs them and re-generates **once** with the results folded into the system prompt
(tools suppressed on the second pass → a final text answer, no further calls).

The built-ins are deliberately **deterministic, single-shot, and side-effect-free** — no shell, no
filesystem, no arbitrary network — because the node is LAN-exposed:

| tool | arguments | what it does |
| ---- | --------- | ------------ |
| `rag_search` | `query`, `top_k?` | searches the ingested RAG corpus (the anchor — grounds answers in your docs) |
| `calculator` | `expression` | evaluates arithmetic (`+ - * / % ^`, parens) via a hand-rolled parser — **no `eval`/exec** |
| `current_datetime` | — | the device's current local date-time (ISO-8601) |
| `unit_convert` | `value`, `from`, `to` | length / mass / temperature conversion |

## Behaviour

- **Built-in called** → the node runs it and returns a **normal assistant message** (final answer),
  not `tool_calls`. The client opted into node execution, so it gets the grounded answer directly.
- **Client tool called** (a tool you supplied in `tools[]` that isn't a built-in) → returned as
  `tool_calls` for **you** to execute, exactly as without `node_tools`. Built-ins and client tools
  coexist. **Your tool name wins on a collision:** if you supply a tool named e.g. `calculator`, the
  node does *not* advertise or execute its own built-in of that name — your call flows back to you.
- The model's typed-argument quirk (`{"type":"STRING","value":"…"}`) is unwrapped automatically.
- A bad tool argument yields an `error: …` result fed back to the model rather than failing the request.
- Retrieved/tool output is fenced as untrusted **DATA** (not instructions) and placed *after* your own
  system prompt, so a poisoned `rag_search` passage can't override your guardrails.

**Cost:** a `node_tools` request that triggers a built-in runs **two** model generations (one to elicit
the call, one to ground the answer) plus the tool's own work — roughly **2× the latency** of a normal
completion, and it holds one admission slot for that duration.

**Precedence:** `node_tools` takes the tool path, so a `response_format` (structured output) on the same
request is ignored (same as supplying `tools`). Works alongside the `rag` chat flag, reasoning, and
history. Streaming returns the final answer as one chunk. Disabled by default — absent `node_tools`,
behaviour is exactly as before.
