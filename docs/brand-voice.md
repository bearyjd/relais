# Relais — Brand Voice & Messaging Guide

Companion to [`DESIGN.md`](../DESIGN.md) (visual identity). That file governs how Relais looks;
this one governs how it sounds. The two share one image: **an amber relay light on a black panel.**
Write like that light — on, steady, unbothered.

---

## 1. Positioning statement

For builders running their own local AI infrastructure, Relais is a headless on-device LLM node
that turns a spare Android phone into an always-on, OpenAI-compatible inference endpoint on your
LAN — private, low-power, zero marginal cost. Unlike Ollama and llama.cpp, which own desktop
CPU/GPU inference but structurally cannot reach a phone's neural silicon, Relais runs on the
hardware they can't: the GPU/NPU in a device you already own, via Google's LiteRT-LM runtime.
It is infrastructure you operate, not a cloud you rent.

---

## 2. Messaging pillars

### Sovereign / local-first
**Claim:** Your model, your hardware, your network — nothing leaves the LAN.
- No cloud dependency, no account, no vendor telemetry pipeline. Requests terminate on hardware you own.
- AGPL-3.0. The whole node is inspectable, forkable, yours.

### Always-on appliance
**Claim:** A node with the light on: plug in a spare phone and it serves inference around the clock at phone-class power draw.
- Headless by design — no chat UI to babysit. It boots, goes live, and transmits.
- Single-digit watts versus a desktop GPU left running; zero marginal cost per token.

### Standard interface, zero lock-in
**Claim:** Relais speaks the OpenAI-compatible API, so existing clients and agents point at it with a base-URL change.
- `/v1/chat/completions`, `/v1/models`, `/v1/embeddings` over the LAN — any OpenAI-compatible SDK just works.
- Swap Relais in or out of a stack without rewriting anything. The interface is the contract.

### Honest infra
**Claim:** Not the fastest tokens/sec — and it says so; the value is always-on, private, zero-marginal-cost inference for small-model and agent/automation workloads.
- Runs on the phone's neural silicon through LiteRT-LM — a hardware class desktop stacks cannot reach.
- Built for the workloads that fit: agents, automation, embeddings, background jobs — not leaderboard benchmarks.

---

## 3. Taglines

**Primary:**

> **Your own relay station for AI.**

**Alternates** (same register — relay, signal, station):

- The node with the light on.
- A spare phone. A live endpoint.
- Inference on your network. Nothing leaves.
- Always on. Always yours.
- Transmitting on your LAN, not theirs.
- Small hardware, standing signal.

---

## 4. The name story

*Relais* is French for relay — the coaching-inn waystations where riders swapped tired horses for
fresh ones so the message could keep moving. That is the job: a small station on a route you own,
keeping your AI traffic moving without it ever leaving roads you control. It belongs to the
VentouxLabs family of waystation names — alongside *navette*, the shuttle — tools for people who
run their own routes.

---

## 5. Boilerplate — three lengths

**~10 words** (badge, store subtitle):

> A spare phone serving a private, OpenAI-compatible LLM on your LAN.

**~25 words** (README intro, directory listing):

> Relais turns a spare Android phone into a headless, always-on LLM node: on-device inference on
> the phone's neural silicon, served as an OpenAI-compatible API over your LAN.

**~50 words** (store description, outreach):

> Relais is a headless on-device LLM node. It runs a model on a spare Android phone's GPU/neural
> silicon and serves it over your LAN as an OpenAI-compatible API — always-on, low-power, private,
> zero marginal cost. Not the fastest tokens/sec; the endpoint that's always there. AGPL-3.0, by
> VentouxLabs.

---

## 6. Voice principles

Relais sounds like a status readout, not a pitch deck. Rules:

1. **Say what the node does, in plain technical terms.** DO: "Serves `/v1/chat/completions` over the LAN." DON'T: "Delivers powerful AI capabilities."
2. **Lead with the tradeoff, then the payoff.** DO: "It won't beat a desktop GPU on throughput. It stays on at a few watts." DON'T: bury or spin the speed gap. Honesty is the moat.
3. **Short declaratives. Readouts, not paragraphs.** If a sentence has two commas and a flourish, cut it into two lines.
4. **Present tense, active voice.** Relais is a live machine: it *serves*, *transmits*, *listens*. Never "will enable you to."
5. **The node is an appliance, not an assistant.** Don't anthropomorphize — Relais has a status light, not a personality. The model answers; the node relays.
6. **Precision over enthusiasm.** Numbers, endpoints, watts, model names. Never an exclamation point. Never "blazing."
7. **State sovereignty; don't preach it.** "Nothing leaves your network" is a fact, not a manifesto. One line, then move on.

### Banned words

revolutionary · seamless(ly) · unleash · game-changing · magic(al) · effortless(ly) ·
supercharge · blazing / blazingly fast · next-generation · cutting-edge · empower ·
frictionless · delightful · "AI-powered" (as a boast) · 10x · unlock · elevate

### Words we use

relay · node · station · endpoint · on-device · headless · sovereign · local-first ·
always-on · appliance · live · transmitting · serving · LAN · signal · beacon ·
low-power · zero marginal cost · inference · operate / operator · hardware you own

---

## 7. Before / after

**1 — the launch line**

- Before: *"Unleash the power of AI with our revolutionary mobile inference platform!"*
- After: *"Relais serves an LLM from a spare phone. Point any OpenAI client at your LAN."*

**2 — the integration claim**

- Before: *"Seamlessly integrate blazing-fast AI into your workflow with zero effort."*
- After: *"Change one base URL. Your agents now run on hardware you own."*

**3 — the honest spec**

- Before: *"Experience the magic of next-generation edge AI — effortless, limitless, everywhere."*
- After: *"It won't win on tokens/sec. It runs all night at a few watts, and nothing leaves your network."*

---

*Use with `DESIGN.md`. If copy and visuals ever disagree on mood, the amber light wins: powered-on, technical, sovereign.*
