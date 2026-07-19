# Tool-call fixtures

**Provenance note (read before trusting these as "real"):** these fixtures are **hand-authored
surrogates**, built to match documented real-model behavior — not captured device output. No
`androidTest` probe log or on-device capture was available in this repo/session to seed them from
(this agent also had no hardware access; see `.agent_native/agent_roadmap.md` item 3 and
`CLAUDE.md`'s hardware-claim discipline). Treat them as **shape fixtures**, good for exercising the
parser/shaping code paths, not as evidence of what the model actually emits.

**How to replace a fixture with a real capture**, next time any of the `androidTest` tool-calling
probes (`ToolCallingProbe.kt`, `MultiTurnReplayProbe.kt`, `NodeToolsProbe.kt`) run on hardware:
those probes already log the raw structured tool-call data via
`Log.i(TAG, "... toolCall[$i] name=${tc.name} args=${tc.arguments}")` (see `ToolCallingProbe.kt`)
— pull the `args=` map out of `adb logcat -s RelaisToolProbe` (or the relevant probe's tag), convert
it to the JSON shape used in these fixtures, and swap the file's `"synthetic"` provenance field to
`"captured"` with the device/model/date it came from.

## Files

- `node-tool-typed-argument.json` — the documented typed-argument quirk from
  `Function_Calling_Guide.md` (`{"type":"STRING","value":"…"}` instead of a bare value) as it would
  appear in a `node_tools` call's arguments object, exercised via
  `cc.grepon.relais.nodetools.NodeToolArgs.unwrap`/`str`/`double`.
- `multi-turn-toolcall-roundtrip.json` — an OpenAI-shape `messages[]` array (client-side
  `tools[]`/`tool_calls` path): a user turn, an assistant `tool_calls` turn, a `role:"tool"` result,
  and a follow-up user turn — exercised via `buildPromptParts` (`RelaisOpenAiParser.kt`), the same
  function `ToolConversationParseTest.kt` covers with inline literals.

Loaded and asserted on by `RelaisToolFixtureReplayTest.kt`
(`Android/src/app/src/test/java/cc/grepon/relais/`), same JVM suite as
`RelaisToolParsingTest`/`ToolConversationParseTest`/`NodeToolsTest` — no device required.
