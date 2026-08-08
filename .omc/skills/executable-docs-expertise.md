---
name: executable-docs-expertise
description: Runbooks and compliance declarations are executable artifacts — reviewing them verifies nothing; only running the commands and cross-checking claims against the implementation finds their defects
triggers:
  - deploy runbook
  - wrangler.toml
  - report-worker
  - store-submission.md
  - Data Safety
  - "bindings should have a string"
  - runbook step fails
  - docs contradict
  - declaration
---

# Docs that instruct are code, and review does not test them

## The Insight

A document that tells someone what to run, or what to type into a form, is an **executable artifact**.
Code review verifies code. It does **not** verify instructions, because reviewing an instruction only
confirms it reads plausibly — not that it works.

Three distinct verification methods, and they find disjoint defects:

| Method | Finds |
|---|---|
| **Review** (read the diff) | logic errors in code |
| **Execute** (run every command, in order, from scratch) | instructions that cannot run |
| **Cross-check** (claim vs. implementation, doc vs. doc) | statements that contradict reality or each other |

On 2026-08-07/08 `report-worker/` shipped with **two clean Codex passes and 22 passing tests**, and
still had four defects. Every one was found by *running the runbook*, not reading it. Then the
compliance docs produced a further five findings, every one from *cross-checking*.

## Why This Matters

The failure is invisible to the checks you trust most. Tests were green because they exercised
`parseReport` and `readBoundedBody` — real code, well tested. Nothing ever made `wrangler` read its
own config, and nothing compared a document's prose against the table three screens below it.

Concretely, what got shipped:

- `wrangler.toml` carried `id = ""`. Wrangler **rejects an empty binding id while parsing the
  config**, before running any command — so `wrangler kv namespace create REPORTS`, the README's own
  step 1, *the command whose entire job is to produce that id*, failed on the config it exists to
  fill in. Error: `"kv_namespaces[0]" bindings should have a string "id" field`.
- Bumping `wrangler` to v4 alone does not install: it peer-requires
  `@cloudflare/workers-types@^5`, an ERESOLVE conflict. A `package.json` that does not resolve is
  worse than the deprecation warning it was fixing.
- `docs/store-submission.md` gate 1 described a careful conditional Data Safety declaration while
  **the primary transcription table in the same file** still read `No` / `None`. Two answers, one
  runbook, and the stale one is the one that looks like the answer sheet.
- The Worker retains a salted SHA-256 of the caller IP for rate limiting. Three documents — plus the
  source header comment — claimed this meant "the only thing collected is the report itself." The
  privacy engineering was sound; the conclusion drawn from it was not.

## Recognition Pattern

Apply this when a change touches any of:

- a **runbook / README with commands** someone will paste
- a **compliance or store declaration** (Data Safety, IARC, permissions, privacy policy)
- **config files consumed by a CLI** (`wrangler.toml`, `build.gradle.kts`, workflow YAML)
- any doc asserting what the code **does** ("nothing is transmitted", "this is never stored")

Red flag phrasing that has been wrong here every time: *"this is fine because it's default-off /
not reversible / user-initiated / only local."* Those are claims about **consequences**, and
consequences are where the reasoning breaks even when each underlying fact is true.

## The Approach

**1. Execute the runbook from a clean state, in written order.** Not "does the tool work" — does
*step 1* work, before step 2 has been done. The empty-`id` defect existed precisely because step 1
was only ever run *after* the state step 1 produces.

**2. Treat your own environment as untested surface.** All three commands were executed and all
reached the auth prompt — on Node 22.22.2. The pinned `wrangler` requires `node >=22`, undeclared, so
a reader on Node 20 hits a wall the execution never revealed. *Running it* is stronger than *reading
it*, and still carries an environment assumption. Ask: what is true about my machine that the
instructions do not state?

**3. Cross-check every claim against the implementation, not the prose.** The IP-hash finding came
from reading `overRateLimit` in `report-worker/src/index.ts`. A doc describing code is a hypothesis
about that code.

**4. Grep the whole repo for the claim you just corrected.** Fixing one instance is the common
failure: the "collects nothing" over-claim lived in `store-submission.md`, `distribution.md`,
`report-worker/README.md`, *and* the source header. When a claim is wrong once, assume it was
copied.

**5. Validate a control before trusting an inference.** "All commands reached the auth error, so the
syntax is valid" only holds if the tool parses args *before* checking auth. Confirmed with a bogus
subcommand returning `Unknown arguments`. Without that control the check proves nothing — and an
uncontrolled inference is exactly how the other defects got shipped.

**6. Fix commits are the highest-risk diff, not the safest.** Written fastest, under most confidence,
against least scrutiny. On this branch a [P1] appeared inside the fix for the previous [P1], twice
running. Re-review the delta; see `[[relais-review-coverage-skew]]`.

## Example

The shape to recognize, generalized from every instance here:

```
observation:  true and verified      ("no raw IP is persisted")
conclusion:   one step too far       ("so we collect nothing")
reality:      a different rule binds (Play counts a retained stable
                                      identifier as collection regardless
                                      of reversibility)
```

The observation is never the bug. The bug is the sentence after it. When writing "…**so** …",
"…**therefore** …", or "…**which means** …" in a doc that instructs, that clause is the claim to
verify — not the fact preceding it.
