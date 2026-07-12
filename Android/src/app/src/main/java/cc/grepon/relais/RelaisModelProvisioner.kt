/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.grepon.relais

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cc.grepon.relais.BuildConfig
import cc.grepon.relais.common.getJsonResponse
import cc.grepon.relais.common.isPixel10
import cc.grepon.relais.data.KEY_MODEL_COMMIT_HASH
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import cc.grepon.relais.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import cc.grepon.relais.data.KEY_MODEL_IS_ZIP
import cc.grepon.relais.data.KEY_MODEL_NAME
import cc.grepon.relais.data.KEY_MODEL_TOTAL_BYTES
import cc.grepon.relais.data.KEY_MODEL_UNZIPPED_DIR
import cc.grepon.relais.data.KEY_MODEL_URL
import cc.grepon.relais.data.AllowedModel
import cc.grepon.relais.data.BuiltInTaskId
import cc.grepon.relais.data.DefaultConfig
import cc.grepon.relais.data.Model
import cc.grepon.relais.data.ModelAllowlist
import cc.grepon.relais.data.RelaisModelRef
import cc.grepon.relais.data.RuntimeType
import cc.grepon.relais.worker.DownloadWorker
import java.io.File
import kotlinx.coroutines.CancellationException

private const val TAG = "RelaisModelProvisioner"

/** Same allowlist host Gallery uses; versioned `{1_0_x}.json` keyed off the app version. */
private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

/**
 * Self-provisioning for the headless node: resolves the configured model from Gallery's allowlist
 * and downloads it (reusing the real [DownloadWorker] transport) if it isn't already on disk, so an
 * operator no longer has to side-load a multi-GB file by hand.
 *
 * All entry points are **blocking** and must be called off the main thread (the node calls them
 * from its `relais-init` thread). They reuse Gallery's download stack as-is — [ModelAllowlist] +
 * `AllowedModel.toModel()` for URL/path construction, [DownloadWorker] for the HTTP download with
 * resume + Bearer auth — and add no new transport.
 *
 * Note: unlike Gallery's [cc.grepon.relais.data.DefaultDownloadRepository], this does not
 * observe progress via `LiveData.observeForever` (which requires the main thread). It enqueues the
 * worker directly and awaits the terminal state via WorkManager's `ListenableFuture`, which is safe
 * from a background thread.
 */
object RelaisModelProvisioner {

  /**
   * Preferred default for a fresh Pixel 10 (Tensor G5): the TPU-native Gemma 4 E2B-it. Kept even
   * after the E4B `isG5Incompatible` load-gate was removed (E4B now serves on G5) — E2B-G5 runs on
   * the Tensor TPU at ~2× the throughput and better power efficiency of E4B on the GPU, which is the
   * right out-of-box default for an always-on node. E4B remains freely selectable.
   *
   * Values resolved via the HF API (commit 361a4010, LFS size 2 588 147 712 bytes) and pinned here
   * so no network is needed to select the default on first boot.
   */
  internal val G5_DEFAULT_REF =
    RelaisModelRef(
      modelId = "litert-community/gemma-4-E2B-it-litert-lm",
      modelFile = "gemma-4-E2B-it.litertlm",
      commitHash = "361a4010ad6d88fc5c86e148e333c0342b99763d",
      sizeInBytes = 2_588_147_712L,
      displayName = "Gemma 4 E2B-it (Tensor G5)",
      source = RelaisModelRef.SOURCE_HUGGINGFACE,
    )

  /**
   * Pure decision function: returns [G5_DEFAULT_REF] iff this is a fresh Pixel 10 whose operator
   * has not yet chosen a model and whose configured id is still the untouched [RelaisConfig.DEFAULT_MODEL_ID].
   * Returns null in all other cases — non-Pixel-10 behavior is byte-identical to before.
   *
   * Unit-testable with no Context or Android SDK.
   */
  internal fun deviceDefaultRef(
    isPixel10: Boolean,
    hasPersistedRef: Boolean,
    currentModelId: String,
  ): RelaisModelRef? =
    if (isPixel10 && !hasPersistedRef && currentModelId == RelaisConfig.DEFAULT_MODEL_ID)
      G5_DEFAULT_REF
    else null

  /** Last resolved/downloaded model path, cached so the engine can re-read it without a refetch. */
  @Volatile private var cachedPath: String? = null

  /** The versioned allowlist URL keyed off the app version, e.g. `…/1_0_15.json`. */
  fun allowlistUrl(): String =
    "$ALLOWLIST_BASE_URL/${BuildConfig.VERSION_NAME.replace(".", "_")}.json"

  /**
   * Resolves the configured model to a [Model] (download URL + on-disk path populated). Prefers a
   * persisted [RelaisModelRef] — which needs no network and works for non-allowlist HF models —
   * and otherwise fetches the allowlist and matches [RelaisConfig.modelId]. Blocking. Throws with a
   * clear message if the allowlist can't be fetched or the configured id isn't in it.
   */
  fun resolveModel(context: Context): Model {
    val modelId = RelaisConfig.modelId(context)
    // Ref fast path: a self-contained ref provisions any selected model offline. Gated to modelId
    // agreement so a bare id change (adb `--es modelId`) bypasses a stale ref and re-resolves the
    // new id via the allowlist below; RelaisConfig.setModelId also drops a diverged ref.
    RelaisConfig.modelRef(context)?.takeIf { it.modelId == modelId }?.let { ref ->
      Log.i(TAG, "Resolving from persisted ref (no allowlist): ${ref.modelId}/${ref.modelFile}")
      // preProcess() populates totalBytes (= sizeInBytes + extras), needed for download progress %.
      return modelFromRef(ref).also { it.preProcess() }
    }
    val url = allowlistUrl()
    // getJsonResponse sets connect/read timeouts, so a wedged network fails this fast on the
    // relais-init thread instead of hanging it indefinitely.
    Log.i(TAG, "Fetching allowlist $url to resolve modelId=$modelId")
    val allowlist =
      getJsonResponse<ModelAllowlist>(url)?.jsonObj
        ?: error("Could not fetch model allowlist from $url (offline?)")
    val allowed =
      allowlist.models.firstOrNull { it.modelId == modelId }
        ?: error("Model id '$modelId' not found in allowlist $url")
    return safeToModel(allowed, modelId, url).also { it.preProcess() }
  }

  /**
   * Wraps [AllowedModel.toModel] so that a null field left by Gson (which ignores Kotlin non-null
   * types) surfaces as an [IllegalStateException] — the existing `error(...)` contract — instead of
   * escaping as a raw [NullPointerException] on the `relais-init` thread. The boot path's upstream
   * handler already catches [IllegalStateException] and shows "Init failed: …"; a raw NPE bypasses
   * it and triggers a watchdog restart loop.
   *
   * Exposed as `internal` so the pure-JVM unit test can exercise the wrapping decision directly,
   * without needing a [Context] or network.
   */
  internal fun safeToModel(allowed: AllowedModel, modelId: String, url: String): Model =
    runCatching { allowed.toModel() }
      .getOrElse { e ->
        if (e is CancellationException) throw e
        error("Allowlist entry for '$modelId' is malformed ($url): ${e.message}")
      }

  /**
   * Builds a [Model] from a [RelaisModelRef] with no network. Reuses [AllowedModel.toModel] so the
   * download URL and on-disk layout are byte-identical to the allowlist path — a curated ref and
   * its allowlist entry resolve to the same file, so a model fetched one way is reused by the other.
   * Typed as a LiteRT-LM chat model (the only kind the node serves); the empty/default config is
   * fine because provisioning only needs the URL, file, version, and size.
   *
   * [Model.name] is the WorkManager unique-work key (`enqueueUniqueWork(model.name, …)`) and the
   * basis of the on-disk dir (`normalizedName`). Two arbitrary HF repos can share a trailing segment
   * (`org-a/gemma-2b` vs `org-b/gemma-2b`), so for HF refs the **full modelId** is the name — which
   * makes the work key unique. (`normalizedName` maps every non-alphanumeric to `_`, so the modelId
   * stays one safe dir segment, but it is NOT injective — `org-a/x` and `org_a/x` collide; on-disk
   * separation of distinct repos rests on the `{name}/{version}/…` layout, and `version` = the
   * commit, which differs.) Curated allowlist refs keep `displayName` so their `name`/path stay
   * byte-identical to the allowlist entry's `toModel()` and the "fetched one way, reused the other"
   * reuse holds.
   */
  private fun modelFromRef(ref: RelaisModelRef): Model =
    AllowedModel(
        name = if (ref.source == RelaisModelRef.SOURCE_HUGGINGFACE) ref.modelId else ref.displayName,
        modelId = ref.modelId,
        modelFile = ref.modelFile,
        commitHash = ref.commitHash,
        description = "",
        sizeInBytes = ref.sizeInBytes,
        defaultConfig = DefaultConfig(null, null, null, null, null, null, null),
        taskTypes = listOf(BuiltInTaskId.LLM_CHAT),
        runtimeType = RuntimeType.LITERT_LM,
      )
      .toModel()

  /**
   * Returns the on-disk model path, downloading it via [DownloadWorker] if absent. Blocking — may
   * take minutes for a multi-GB model. [onProgress] receives 0..100 while downloading. For a
   * license-gated repo, set [RelaisConfig.setHfToken] first or the download 401s.
   */
  fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}): String {
    // Control-panel phase line (AUDIT.md §4.2/Q6): every path through this function starts in
    // RESOLVING — the offline fast paths below finish before ever reaching DOWNLOADING, so a node
    // that boots from a persisted/pre-staged model never shows a stale phase from a prior attempt.
    RelaisNodeProgress.phase = ProvisionPhase.RESOLVING
    // Fresh Pixel 10 (Tensor G5): default to the faster TPU-native E2B-G5 (see [G5_DEFAULT_REF])
    // before ANY provisioning. setModelRef clears the stale modelPath and sets modelId=E2B, so the
    // fast-paths below skip and the ref fast-path in resolveModel provisions E2B. Self-healing if
    // apply() races. (E4B is no longer blocked on G5 — this is a perf-preference default, not a gate.)
    deviceDefaultRef(isPixel10(), RelaisConfig.modelRef(context) != null, RelaisConfig.modelId(context))?.let { ref ->
      Log.i(TAG, "Fresh Pixel 10: defaulting to G5-compatible ${ref.modelId}")
      RelaisConfig.setModelRef(context, ref)
    }
    // Capture the id AFTER substitution so the issue-#11 drift guard doesn't see false drift
    // (the id is now E2B, and idAtStart must match for the persist gate to pass).
    val idAtStart = RelaisConfig.modelId(context)
    // Offline-safe fast path: a previously provisioned file still on disk needs no network. This
    // lets a rebooted / watchdog-restarted node boot without the allowlist when nothing to download.
    RelaisConfig.modelPath(context)?.let { saved ->
      if (File(saved).exists()) {
        Log.i(TAG, "Using persisted model path (no allowlist fetch needed): $saved")
        cachedPath = saved
        return saved
      }
    }
    // Offline-safe fast path 2: a model an operator pre-staged at the conventional side-load
    // location ([RelaisEngine.defaultModelPath], e.g. pushed via adb into the app files dir) is
    // adopted as-is, so a fresh install whose model is already on disk boots LIVE without
    // re-downloading multiple GB over a slow link. Persisting it here means subsequent boots take
    // fast path 1 above. Gated to the default model id because that path's file name is the default
    // model's file specifically; a non-default id is resolved against the allowlist below instead.
    if (RelaisConfig.modelId(context) == RelaisConfig.DEFAULT_MODEL_ID) {
      val staged = File(RelaisEngine.defaultModelPath(context))
      // length() > 0 (returns 0 when absent) also rejects an interrupted/empty `adb push` so a
      // 0-byte stub isn't adopted and then fails opaquely later in Engine init.
      if (staged.length() > 0L) {
        Log.i(TAG, "Adopting pre-staged model at default location (no download): ${staged.path}")
        return remember(context, staged.absolutePath, persistForId = idAtStart)
      }
      // If the staging dir exists but no app-readable model does, an operator likely side-loaded a
      // file the app can't read: an adb-pushed file is owned by `shell` with no "others" bit, so
      // File.length() reads 0 from the app uid and we silently fall through to a multi-GB download.
      // The app can't reliably stat the file itself in that state, so key the hint off the dir.
      if (staged.parentFile?.exists() == true) {
        Log.w(
          TAG,
          "Staging dir ${staged.parent} exists but no app-readable model — a side-loaded file may " +
            "be unreadable to the app (check perms: chmod 0644 the model, 0755 its dir). " +
            "Falling back to download.",
        )
      }
    }
    val model = resolveModel(context)
    val path = model.getPath(context)
    if (File(path).exists()) {
      Log.i(TAG, "Model already present, skipping download: $path")
      return remember(context, path, persistForId = idAtStart)
    }
    model.accessToken = RelaisConfig.hfToken(context)
    Log.i(TAG, "Model absent; downloading ${model.name} from ${model.url} -> $path")
    download(context, model, onProgress)
    require(File(path).exists()) { "Download reported success but file is missing: $path" }
    Log.i(TAG, "Model provisioned: $path")
    return remember(context, path, persistForId = idAtStart)
  }

  /**
   * True iff a freshly provisioned path may be persisted: no drift, or drift unknown.
   *
   * When [provisionedForId] is null the caller opted out of drift detection (preserves legacy
   * behavior). When set, the path is only persisted if the current model id still matches the one
   * captured at the start of provisioning — guarding against the race in issue #11 where the
   * operator changes the model mid-download and the stale path would otherwise poison the next boot.
   */
  internal fun shouldPersistPath(provisionedForId: String?, currentId: String): Boolean =
    provisionedForId == null || provisionedForId == currentId

  /**
   * Caches the resolved path in-memory (always — it's what this boot actually provisioned) and
   * persists it to [RelaisConfig] only if the model id hasn't drifted since provisioning started.
   * Pass [persistForId] = the id captured at [ensureModel] entry to enable the drift guard;
   * omit it (null) to always persist (legacy / callers without an id snapshot).
   */
  internal fun remember(context: Context, path: String, persistForId: String? = null): String {
    cachedPath = path
    // Read the current id once: it drives the gate AND the drift warning, and re-reading risks a
    // TOCTOU mismatch between the decision and the logged value.
    val currentId = RelaisConfig.modelId(context)
    if (shouldPersistPath(persistForId, currentId)) {
      RelaisConfig.setModelPath(context, path)
    } else {
      Log.w(
        TAG,
        "Model id changed mid-provision (now $currentId, provisioned for $persistForId); " +
          "not persisting stale path",
      )
    }
    return path
  }

  /**
   * The last provisioned path (in-memory, then persisted), or the legacy hardcoded location as a
   * fallback (e.g. pre-provision). The persisted path is only used if the file still exists.
   */
  fun cachedPathOrDefault(context: Context): String =
    cachedPath
      ?: RelaisConfig.modelPath(context)?.takeIf { File(it).exists() }
      ?: RelaisEngine.defaultModelPath(context)

  /**
   * Enqueues [DownloadWorker] with the same [Data] contract as
   * [cc.grepon.relais.data.DefaultDownloadRepository.downloadModel] (same unique-work key
   * so the two never double-enqueue) and blocks until the work reaches a terminal state.
   */
  private fun download(context: Context, model: Model, onProgress: (Int) -> Unit) {
    val workManager = WorkManager.getInstance(context)
    val inputBuilder =
      Data.Builder()
        .putString(KEY_MODEL_NAME, model.name)
        .putString(KEY_MODEL_URL, model.url)
        .putString(KEY_MODEL_COMMIT_HASH, model.version)
        .putString(KEY_MODEL_DOWNLOAD_MODEL_DIR, model.normalizedName)
        .putString(KEY_MODEL_DOWNLOAD_FILE_NAME, model.downloadFileName)
        .putBoolean(KEY_MODEL_IS_ZIP, model.isZip)
        .putString(KEY_MODEL_UNZIPPED_DIR, model.unzipDir)
        .putLong(KEY_MODEL_TOTAL_BYTES, model.totalBytes)
    model.accessToken?.let { inputBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, it) }

    val request =
      OneTimeWorkRequestBuilder<DownloadWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(inputBuilder.build())
        .addTag("modelName:${model.name}")
        .build()

    // REPLACE + unique key on model.name matches Gallery's policy: a single in-flight download.
    workManager.enqueueUniqueWork(model.name, ExistingWorkPolicy.REPLACE, request)

    val totalBytes = model.totalBytes
    var lastReceived = 0L
    var lastProgressAt = System.currentTimeMillis()
    while (true) {
      val info: WorkInfo? = workManager.getWorkInfoById(request.id).get()
      when (info?.state) {
        WorkInfo.State.SUCCEEDED -> {
          onProgress(100)
          return
        }
        WorkInfo.State.FAILED -> {
          val msg = info.outputData.getString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE) ?: "unknown error"
          error("Model download failed: $msg")
        }
        WorkInfo.State.CANCELLED -> error("Model download cancelled")
        WorkInfo.State.RUNNING -> {
          val received = info.progress.getLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L)
          if (received > lastReceived) {
            lastReceived = received
            lastProgressAt = System.currentTimeMillis()
            RelaisNodeProgress.onDownloadProgress(received, totalBytes)
            if (totalBytes > 0) onProgress(((received * 100) / totalBytes).toInt().coerceIn(0, 99))
          }
        }
        else -> {
          // ENQUEUED / BLOCKED / null: the worker hasn't started yet (e.g. expedited quota spent →
          // deferred to regular scheduling). Keep the stall clock pinned to "now" so waiting-to-run
          // never counts as a stall — the timeout must only measure a RUNNING job making no progress.
          lastProgressAt = System.currentTimeMillis()
        }
      }
      // Guard against a hung worker silently blocking the init thread forever: bail if a RUNNING
      // download makes no byte progress within the stall window. Legitimate slow downloads still
      // advance, so this only trips on a genuine stall (dead socket), not on size or queue wait.
      if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
        workManager.cancelWorkById(request.id)
        error("Model download stalled (no progress for ${STALL_TIMEOUT_MS / 1000}s)")
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
  }

  private const val POLL_INTERVAL_MS = 1000L
  private const val STALL_TIMEOUT_MS = 120_000L
}
