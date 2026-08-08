---
name: litertlm-native-api-probe-first
description: litertlm 0.11.0 capability labels in docs/litertlm-native-api.md are wrong in BOTH directions — probe on-device before designing on any "available"/"unverified" claim
triggers:
  - litertlm native api
  - getBenchmarkInfo
  - Benchmark is not enabled
  - BenchmarkParams
  - enableBenchmark
  - message.channels
  - enable_thinking
  - reasoning_content
  - litertlm-native-api.md
  - unexploited hooks
  - ExperimentalFlags
---

# litertlm 0.11.0 — verify native capabilities with an on-device probe, both directions

## The Insight

`docs/litertlm-native-api.md` labels each native hook "available", "unverified", or "see verdict".
Those labels are **claims about the API surface, not guarantees about runtime behavior** — and they
have been wrong in *both* directions. A hook the doc calls "available" can be unreachable through the
public Kotlin API; a hook it calls "unverified" can work cleanly. Treat every label as a hypothesis
to test on-device before you design code (or a fallback) on it. The repo's `native-API-first` rule
tells you to check the doc; this skill adds: **the doc itself must be re-verified by probe**, because
its optimism and its pessimism have both burned this project.

## Why This Matters

If you trust an "available" label and build on it, you can ship a feature whose core call throws at
runtime. If you trust an "unverified"/"not available" label, you hand-roll a fallback the native API
already does better (this has happened 3+ times here: feature-03 replay, feature-04 tool scraping,
feature-05 constrained-decoding). One ~30-min non-destructive `am instrument` probe on rango (E2B
staged) resolves it before any production code is written.

## Recognition Pattern

You're about to: surface a new litertlm capability through the OpenAI API; design a fallback because
"the API doesn't do X"; or rely on an `ExperimentalFlags` toggle. The capability is marked
"available" or "unverified" in `litertlm-native-api.md` and has no `*Probe.kt` backing it.

## The Approach

1. Before building, write a throwaway `*Probe.kt` (androidTest, `assumeTrue`-gated on the model path)
   that exercises the exact call on the resident `Engine`/`Conversation` you'll use in production —
   not a standalone helper. Run it on rango/E2B via `am instrument` (non-destructive; no uninstall).
2. Make the probe **fail-soft**: log partial results in a `finally`, never throw before logging.
   Bound generation (`maxNumTokens` small, terse prompt) so a leg actually completes — an unbounded
   "show your reasoning" prompt ran ~8 min and timed out with zero captured data the first time.
3. When a flag/method throws, **read the exception text as a roadmap** and then confirm against the
   AAR with `scripts/dump-litertlm-api.sh <ver> <Class>` (javap). A native error can name an internal
   type that has **no public Kotlin counterpart** — which means the path is closed, not "set one more
   field."
4. Only after the probe gives a verdict do you write production code. Then fold the verdict back into
   `litertlm-native-api.md` (§ tables) and keep the probe (renamed) as the on-device verification.

## Example — two verdicts from one probe (litertlm 0.11.0, rango/Tensor-G5, Gemma-4 E2B)

**"available" but actually a DEAD END — per-request benchmark / exact tokens.** The doc listed
`getBenchmarkInfo()` / `enableBenchmark` as "available" for real prefill/decode tok/s + exact token
counts on the live path. On-device, `ExperimentalFlags.enableBenchmark = true` then
`conversation.getBenchmarkInfo()` threw:
`INTERNAL: Benchmark is not enabled. Please make sure the BenchmarkParams is set in the EngineSettings.`
`javap` on the 0.11.0 AAR: **no public `BenchmarkParams` and no `EngineSettings` class** —
`EngineConfig(modelPath, backend, visionBackend, audioBackend, maxNumTokens, maxNumImages, cacheDir)`
exposes no benchmark hook. The only populated `BenchmarkInfo` comes from the standalone
`BenchmarkKt.benchmark(modelPath, backend, …)` one-shot, which re-loads the model and **cannot run on
the resident serving engine**. So exact `prompt_tokens` is unreachable via this API — it would need a
tokenizer or the low-level `Session` token counts. (Confirms `RelaisEngine.kt`'s SPIKE-FINDINGS Q1
comment; the doc's "available" was wrong.)

**"unverified" but actually WORKS — reasoning channel.** The doc's §6 marked `Channel` /
`message.channels` "unverified". On-device, passing
`extraContext = mapOf("enable_thinking" to "true")` to `sendMessageAsync` made Gemma-4 E2B populate a
separate `message.channels["thought"]` stream (81/83 callbacks) while the visible answer
(`message.toString()`) stayed clean (`"43"`, no `<think>` leakage). Without that extraContext key, the
channel is empty (`channelKeys=[]`) — and `RelaisEngine` passes `emptyMap()` by default, so the
capability sits dormant. The reasoning arrives as **per-token deltas** (not cumulative), so it streams
exactly like visible tokens → OpenAI `reasoning_content`. Mechanism was hiding in inherited gallery
code (`LlmChatViewModel` sets the key; `LlmChatModelHelper` reads `channels["thought"]`).
