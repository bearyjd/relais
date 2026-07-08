# Relais Privacy Policy

**Effective date:** 2026-07-07 · **Publisher:** VentouxLabs · **App:** Relais (`com.ventouxlabs.relais` and its IzzyOnDroid/GitHub variants)

Relais turns a phone you own into a private AI inference node: it runs language models **on the
device** and serves an OpenAI-compatible API **on your local network**. It is built so that your
prompts, documents, audio, and images never leave hardware you control.

## The short version

- **All AI processing happens on your device.** Prompts, chat history, transcribed audio, analyzed
  images, generated images, and retrieval documents are processed by models running locally. None
  of it is sent to us or to any cloud AI service.
- **We collect nothing.** Relais has no analytics, no telemetry, no crash reporting, no ads, no
  tracking, and no account system. There is no VentouxLabs server; we never see your data.
- **You control the network surface.** The API is served only on your local network, protected by
  an access key generated on your device. You start and stop it.

## What the app stores on your device

- **Models** you download (multi-gigabyte files, in the app's private storage).
- **Configuration**, including the node's access key and — only if you provide one — a Hugging Face
  access token. Both are stored in Android's encrypted preferences.
- **Content you put into features**: chat/session state, prompt templates, documents you ingest for
  retrieval (RAG), and batch jobs. All local, all deleted when you clear the app's data or
  uninstall.

## When the app talks to the internet

Relais makes no background connections to us — there is nothing to connect to. It reaches the
internet only for these specific, user-visible purposes:

1. **Downloading models.** When you pick a model, the app fetches it from
   [Hugging Face](https://huggingface.co) (`huggingface.co`) or, for the built-in Gallery catalog,
   from Google's model hosting (`dl.google.com`). Model searches you type in the model selector are
   sent to Hugging Face as search queries.
2. **Your Hugging Face token** (optional, for license-gated models) is sent **only** to
   `huggingface.co` as authentication on those downloads, and nowhere else.
3. **Model catalog + update check.** The app fetches its curated model list from the upstream
   project's GitHub repository (`raw.githubusercontent.com`) and checks GitHub (`api.github.com`)
   for a newer app release. These are plain HTTPS fetches; they carry no personal data beyond what
   any web request exposes (your IP address).
4. **Destinations you configure.** If you set up a webhook for batch-job results, or load an agent
   skill from a URL, the app sends/fetches exactly what you configured, to the destination you
   chose. These features are off until you configure them.

Requests from your own apps and devices to the node's LAN API stay on your network; the node also
announces itself on your local network via mDNS (service name and port — no personal data) so
clients can discover it.

## Permissions, in plain language

- **Microphone / Camera** — used only when you actively use voice input, audio transcription, or
  image analysis; the audio/images are processed on-device and are not uploaded anywhere.
- **Notifications** — so the always-on node can show its status.
- **Network/Internet** — for the model downloads and LAN serving described above.
- **NFC** — only if you enable NFC workflow tags.
- **Start at boot / keep awake** — so the node can run unattended, if you enable it.

## Data sharing and selling

We don't collect your data, so we cannot share or sell it. The app sends data to third parties only
in the user-directed cases listed above (Hugging Face downloads with your own token; webhooks and
skill URLs you configure). Those services' own privacy policies apply to what they receive.

## Data retention and deletion

Everything lives on your device. Delete any of it at any time via the in-app controls, Android's
*Clear data*, or by uninstalling the app. There is nothing to delete on our side.

## Children

Relais is developer/infrastructure tooling and is not directed at children.

## Changes

We'll update this policy if the app's data behavior changes, and note changes in the app's
changelog. The canonical version lives in the app's source repository
(`docs/privacy-policy.md`).

## Contact

VentouxLabs — bryn@ventouxadvisoryco.com
