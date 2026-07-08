# Relais distribution & release runbook

How Relais is signed, built, and published to its three channels. This doc currently covers the
**release pipeline** (signing + CI). Store-listing metadata (Fastlane), the Play policy paperwork
(privacy policy / Data Safety / content rating), and the IzzyOnDroid RFP are follow-up sub-projects and
will be appended here as they land.

## Channels & artifacts

| Channel | Variant | Gradle task | Artifact | applicationId |
|---|---|---|---|---|
| Google Play | `fullPlaysafe` | `bundleFullPlaysafeRelease` | **AAB** | `com.ventouxlabs.relais` |
| IzzyOnDroid | `fullOpen` | `assembleFullOpenRelease` | **APK** | `com.ventouxlabs.relais.izzy` |
| GrapheneOS / GitHub | `degoogledOpen` | `assembleDegoogledOpenRelease` | **APK** | `com.ventouxlabs.relais.degoogled` |

The three are signed with **one** release key (below). `namespace` stays `cc.grepon.relais`; appId is set
per-channel in `build.gradle.kts` (`androidComponents.onVariants`). `degoogledPlaysafe` is intentionally
not shipped. F-Droid's main repo is **not** a target — the bundled `litertlm`/`litert` are proprietary
prebuilt native blobs, so the FOSS-only main repo can't accept any variant; IzzyOnDroid (which flags the
`NonFreeDep` anti-feature) is the F-Droid-ecosystem home.

## The signing key (generate once, never rotate)

IzzyOnDroid and Obtainium/GrapheneOS users update **in place**, which requires the APK signature to stay
constant forever. **If this key is lost or rotated, every sideload user must uninstall + reinstall.** Back
up the `.jks` and its passwords offline (e.g. a password manager + an encrypted backup).

```bash
keytool -genkeypair -v -keystore relais-release.jks -alias relais \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
# answer the prompts (CN, org, etc.); set a strong store password and key password.

base64 -w0 relais-release.jks   # copy the output into the RELEASE_KEYSTORE_BASE64 secret
```

For Google Play, **enrol in Play App Signing**: this key is used as the **upload key** (it signs the AAB
you upload); Google holds and manages the actual app-signing key. If the upload key is ever compromised it
can be reset with Google — unlike the sideload signature, which cannot change.

## GitHub Actions secrets

Add under **repo → Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the `base64 -w0` output of `relais-release.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `relais` |
| `RELEASE_KEY_PASSWORD` | key password |

`build.gradle.kts` reads `RELEASE_STORE_FILE` + the three above from the environment. When they are
**absent**, the `release` build type falls back to debug signing — so local builds, contributor builds,
and `build_android.yaml` work with no secrets. The release CI sets `RELEASE_STORE_FILE` to the decoded
keystore path.

## Cutting a release

1. Bump `versionCode` / `versionName` in `Android/src/app/build.gradle.kts`, commit.
2. Tag and push: `git tag v1.0.16 && git push origin v1.0.16`.
3. `.github/workflows/release.yaml` runs on the `v*` tag: decodes the keystore, builds the AAB + the three
   APKs, re-runs the hard gates (**GMS=0** on degoogled, **playsafe-permission removal**, **16 KB**
   native alignment), `apksigner verify`s the APKs, and opens a **draft GitHub Release** with the AAB +
   the `fullOpen` and `degoogledOpen` APKs attached.
4. Review the draft Release, then publish it. The `fullOpen`/`degoogledOpen` APKs are now the
   GitHub-Release assets IzzyOnDroid and Obtainium track.
5. **Google Play (manual, first time):** upload the AAB to the Play Console, enrol in Play App Signing,
   complete the store listing + policy forms (separate sub-project), and submit for review. Subsequent
   uploads reuse the same upload key.

### Testing the pipeline without cutting a release
Run the **Release** workflow via *Actions → Release → Run workflow* (`workflow_dispatch`). It builds + gates
the artifacts and uploads them as a workflow artifact instead of creating a GitHub Release. (Without the
`RELEASE_*` secrets it builds debug-signed — fine for exercising the pipeline, not for store upload.)

## Notes
- `release.yaml` also assembles the `fullPlaysafe` **APK** purely so the permission + alignment gates run
  on the same merged manifest/libs the Play AAB ships; that APK is not published.
- Editing files under `.github/workflows/` can fail to auto-trigger the *Build Android APK* workflow on a
  PR (a known GitHub quirk); verify `release.yaml` via `workflow_dispatch` or a throwaway tag.


## Privacy policy (T16/#120)

- **Source of truth:** `docs/privacy-policy.md`. **Hosted copy:** `docs/privacy-policy.html`
  (same content, brand-styled), deployed by `.github/workflows/static.yml` to GitHub Pages at
  **`https://bearyjd.github.io/relais/privacy-policy.html`** — this is the URL for the Play
  Console *Privacy policy* field and the IzzyOnDroid metadata. Editing either policy file must
  keep the two in sync (the HTML is the rendered copy of the md).
- Hosting decision: GitHub Pages via the existing `static.yml` artifact deploy (no Jekyll), chosen
  over a raw-file URL because Play requires a real, stable web page. If a `ventouxlabs` domain
  materializes later, point it at the same file and update the console field.

## Play Data Safety form — question → answer mapping (T17/#121)

Variant under review: **`fullPlaysafe`** (`com.ventouxlabs.relais`). Every answer below is
traceable to code; the release workflow's permission gate enforces the playsafe manifest strips
(exact alarms, battery-optimization request, `READ_CALENDAR`, the notification listener +
`TriageControlActivity`).

**Overview answers**

| Console question | Answer | Code-level justification |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | No analytics/telemetry/crash-reporting SDKs (Firebase removed in the fork; no `com.google.firebase`/`analytics` deps in `build.gradle.kts`). There is no developer server; nothing is transmitted to VentouxLabs. All egress is user-initiated to user-chosen services (below), which Play's Data Safety guidance treats under the user-initiated-action / service-provider carve-outs — and none of it reaches the developer. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (for what little transits) | Model downloads + HF search/resolve are HTTPS (`RelaisHuggingFace`, `ImageModelProvisioner`, `EmbeddingModelProvisioner`, `DownloadWorker`); allowlist/release checks are HTTPS; webhooks are HTTPS-only unless the operator explicitly allowlists a host (`WebhookGuard`, `RelaisConfig.KEY_WEBHOOK_ALLOWLIST`); LAN API offers HTTPS :8443 (self-signed) alongside loopback HTTP :8080. |
| Do you provide a way for users to request that their data is deleted? | **Data not collected** (n/a) | Nothing exists server-side; on-device data is deleted via in-app controls / Clear data / uninstall — stated in the privacy policy. |

**Per-data-type notes a reviewer may probe (all "not collected"):**

| Data type | Why "not collected" holds |
|---|---|
| Messages / "Other in-app messages" | Prompts + completions processed in-process by the resident LiteRT-LM engine (`RelaisEngine`); never transmitted off-device by the app. Clients on the operator's LAN receive responses over the operator's own network — the app is the server, not a collector. |
| Audio / Photos+videos | `RECORD_AUDIO`/`CAMERA` feed on-device transcription (`/v1/audio/transcriptions` bridges to the local engine) and on-device vision; no upload path exists in the code. |
| Personal identifiers / credentials | The HF token is user-provided, stored in `EncryptedSharedPreferences` (`RelaisConfig`), sent solely as a Bearer to `huggingface.co` on downloads the user initiates (one-way auth-drop on redirect, `ImageModelProvisioner`/`SkillSourceFetcher` patterns). The node access key never leaves the device except displayed/shared by the operator. |
| Device IDs | Not read, not transmitted. |
| Web browsing / location / contacts / calendar / health / financial | No code paths; `READ_CALENDAR` is stripped from the playsafe manifest (`src/playsafe/AndroidManifest.xml`, CI-gated). |

**Egress inventory backing the "No" (complete, from source sweep 2026-07-07):** `huggingface.co`
(model search/resolve/download + optional user token), `dl.google.com` (inherited Gallery catalog
model downloads, `DownloadAndTryButton`/`DownloadWorker`), `raw.githubusercontent.com` (model
allowlist JSON, `ModelManagerViewModel`), `api.github.com` (release check,
`NewReleaseNotification`), operator-configured webhooks (`WebhookDelivery`, SSRF-guarded +
IP-pinned), operator-provided skill URLs (`SkillSourceFetcher`, pinned). Plus LAN-only serving +
mDNS advertisement (`RelaisDiscovery`: service name/port, no personal data).

## Play content rating (IARC) — questionnaire mapping (T17/#121)

- **Category:** Utility / productivity / developer tool.
- Violence / sexuality / language / controlled substances / gambling: **None** — the app ships no
  content; it is an inference server + control panel.
- **User interaction / UGC:** the app itself has no social features, sharing hub, or user community;
  content is generated locally on the operator's request. Answer "users can generate content via
  on-device AI" where the questionnaire asks about AI.
- **AI-generated content: Yes** — on-device LLM chat (+ image generation on this variant via
  sd.cpp). Mitigations to declare: fully local processing, operator-only access behind a
  device-generated bearer key, Gemma models ship under Google's Gemma Terms +
  Prohibited Use Policy (linked in-app: `ai.google.dev/gemma`).
- Unrestricted internet: the app accesses the internet (model downloads), but provides no browser.
- **Expected rating:** Everyone / PEGI 3 tier with the AI-content disclosure. (IARC's generative-AI
  question set is new — answer honestly per above; the rating may come back higher in some locales.)

**FGS disclosure (console "App content" page, related):** foreground service type `dataSync`
(`RelaisNodeService`) — declared use: keeping the local inference server resident while the
operator's devices use it; started only by explicit operator action (START / boot-start opt-in).
