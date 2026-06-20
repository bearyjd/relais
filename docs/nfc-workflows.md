# NFC Workflows (Feature #15)

Physical, zero-friction triggers for the on-device model: tap the phone to an NFC tag to run a saved
prompt template on the resident engine, with the result posted as a private notification. Fully
on-device and offline — no app to open, no typing, no network.

## How it works

1. An NDEF tag encodes `cc.grepon.relais://workflow/<templateId>` (optionally `?q=<inline prompt>`).
2. Tapping the tag launches `RelaisNfcActivity` (a no-UI trampoline) via `ACTION_NDEF_DISCOVERED`.
3. It resolves `<templateId>` against the #12 prompt-template registry (`WorkflowRegistry.resolve`).
4. It runs the template's **system** prompt with the inline `q` (or a default prompt) on the resident
   model via `RelaisShareService`, which posts a `VISIBILITY_PRIVATE` result notification.

## Trust model (operator decision)

- **Opt-in.** `RelaisConfig.nfcEnabled` defaults **false**. A tag does nothing until you enable NFC in
  the control panel. Disabling makes every tap a no-op again.
- **Any Relais-scheme tag triggers** (when enabled). An NDEF tag is unauthenticated, so the boundary is
  physical: someone who can tap a tag to your **unlocked** phone can trigger a workflow. Acceptable
  because the only effect is *inference → a notification*.
- **Tag content is untrusted.** The inline `q` prompt is attacker-influenced (anyone can write a tag),
  so it is length-capped (`MAX_PROMPT = 4096`) and run through **plain inference only** — no node-tools
  (#9), no LAN egress, no device actions. Worst case is a misleading notification.
- **Never cold-starts.** Like the share/widget paths, a tap is ignored if the node isn't already
  serving (`RelaisInference.isReady`).

A stricter posture (only tags carrying a per-node HMAC, rejecting planted tags) was considered and
deferred — see the AskUserQuestion decision in the session handoff.

## Writing tags

Control panel → enable NFC → **WRITE NFC TAG ›** opens `NfcWriteActivity`: pick a workflow (template),
then hold a blank/rewritable tag to the phone. It writes `cc.grepon.relais://workflow/<id>` via
foreground dispatch and reports success / read-only / too-small / failure.

## Components

- `nfc/NfcWorkflowParser` — **pure** parse/build of the workflow URI (unit-tested on the JVM).
- `nfc/NfcTagWriter` — writes the NDEF URI record to an `Ndef`/`NdefFormatable` tag.
- `nfc/RelaisNfcActivity` — exported NDEF trampoline (gate → parse → resolve → run).
- `nfc/NfcWriteActivity` — internal Compose writer (Relais palette).
- `RelaisShareService` — reused; gained an optional `EXTRA_SYSTEM` override so NFC runs the template's
  system prompt instead of the share default.
- `RelaisConfig.nfcEnabled` — the opt-in flag.

## Notes

- The result reuses the share `relais_share_result` channel (`VISIBILITY_PRIVATE`, amber, "Relais ·
  result"). The answer is also copied to the clipboard (inherited from the share path).
- NFC requires the device to have an NFC adapter; the control-panel row is hidden otherwise.
- minSdk 31 covers all NFC APIs used (`NfcAdapter`, `Ndef`, `NdefRecord.createUri`, foreground dispatch
  with a `FLAG_MUTABLE` PendingIntent).
