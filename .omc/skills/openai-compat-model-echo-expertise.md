---
name: openai-compat-model-echo
description: OpenAI/Cohere-compatible response fields (model, etc.) are ECHO contracts, not REPORT contracts — echo the client's value when present, only substitute ground truth as a fallback
triggers:
  - v1/embeddings model field
  - v1/rerank model field
  - resolveEmbeddingModel
  - RelaisConfig.modelId
  - embedder.modelId
  - drop-in fidelity
  - OpenAI compatible response
---

# `model` (and similar) fields in OpenAI/Cohere-shaped endpoints are echoes, not reports

## The Insight

Issue #190 looked like a simple truthfulness bug: `/v1/rerank` and `/v1/embeddings` returned
`RelaisConfig.modelId(context)` (the resident *LLM's* id) in the response `model` field, even though
an EmbeddingGemma *embedder* — a completely different model — actually did the work. The obvious fix
is "report the real model that ran": `val model = embedder.modelId`. That fix shipped in #191 and was
about to ship again in #192's first commit.

A devil's-advocate review caught the real bug underneath the obvious one: OpenAI's `/v1/embeddings`
and Cohere's `/v1/rerank` both **echo the client's requested `model` field back verbatim** — it is not
a "what actually ran" report, it's a round-trip acknowledgment. Drop-in clients (LiteLLM, LangChain,
Cohere SDKs) key routing/caching/logging off `response.model == request.model`. Always overriding it
with ground truth — even *correct* ground truth — breaks that contract for every client that sets
`model` explicitly.

## Why This Matters

The two fixes (#191, then the first commit of #192) both "worked" in the narrow sense of returning a
truthful value and passing every test written for them, because the tests only checked "is the
resident-LLM-name bug gone," never "does this still round-trip what the client sent." A field can be
100% *accurate* and still be a *contract violation* — accuracy and contract-fidelity are different
axes, and a naive read of "issue #190: response echoes the wrong model" pattern-matches to "so make it
echo the right model" instead of "so make it echo the *client's* model, correctly falling back only
when they didn't send one."

## Recognition Pattern

Any response field in a `/v1/*` handler that mirrors an OpenAI, Cohere, or similar third-party API
shape (`model`, but also things like `id`, `object`, echoed request params) is a candidate for this
trap. Ask **before** changing what value populates the field: "does the upstream spec (OpenAI/Cohere
docs) define this field as an echo of the request, or a report of server state?" Don't assume from the
field's *name* — `model` sounds like "the model that ran," but the spec says otherwise.

## The Approach

For any such field: `requestedValue?.takeIf { it.isNotBlank() } ?: groundTruthValue` — echo when the
client supplied one, substitute the true server-side value ONLY as a fallback for the omitted case.
Never `groundTruthValue` unconditionally, even when it is more "correct" — correctness is not the
spec's contract here. See `resolveEmbeddingModel` in `RelaisHttpServer.kt`, shared by both
`handleEmbeddings` and `handleRerank` so the two RAG-triad endpoints can't drift apart on this again.

When reviewing this class of "make the response field more truthful" change, run a devil's-advocate
pass before merging — this is exactly the shape of bug that looks fixed, passes tests, and ships a
second contradiction (see #191, merged with the bug, then corrected by #192) unless someone
deliberately argues the client's side of the contract.
