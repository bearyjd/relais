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
