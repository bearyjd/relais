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
import cc.grepon.relais.data.Model
import cc.grepon.relais.data.ModelAllowlist
import cc.grepon.relais.worker.DownloadWorker
import java.io.File

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

  /** Last resolved/downloaded model path, cached so the engine can re-read it without a refetch. */
  @Volatile private var cachedPath: String? = null

  /**
   * Fetches the allowlist and resolves the [RelaisConfig.modelId] entry to a [Model] (with download
   * URL + on-disk path populated). Blocking network call. Throws with a clear message if the
   * allowlist can't be fetched or the configured id isn't in it.
   */
  fun resolveModel(context: Context): Model {
    val modelId = RelaisConfig.modelId(context)
    val version = BuildConfig.VERSION_NAME.replace(".", "_")
    val url = "$ALLOWLIST_BASE_URL/$version.json"
    Log.i(TAG, "Fetching allowlist $url to resolve modelId=$modelId")
    val allowlist =
      getJsonResponse<ModelAllowlist>(url)?.jsonObj
        ?: error("Could not fetch model allowlist from $url (offline?)")
    val allowed =
      allowlist.models.firstOrNull { it.modelId == modelId }
        ?: error("Model id '$modelId' not found in allowlist $url")
    // preProcess() populates totalBytes (= sizeInBytes + extras), needed for download progress %.
    return allowed.toModel().also { it.preProcess() }
  }

  /**
   * Returns the on-disk model path, downloading it via [DownloadWorker] if absent. Blocking — may
   * take minutes for a multi-GB model. [onProgress] receives 0..100 while downloading. For a
   * license-gated repo, set [RelaisConfig.setHfToken] first or the download 401s.
   */
  fun ensureModel(context: Context, onProgress: (Int) -> Unit = {}): String {
    // Offline-safe fast path: a previously provisioned file still on disk needs no network. This
    // lets a rebooted / watchdog-restarted node boot without the allowlist when nothing to download.
    RelaisConfig.modelPath(context)?.let { saved ->
      if (File(saved).exists()) {
        Log.i(TAG, "Using persisted model path (no allowlist fetch needed): $saved")
        cachedPath = saved
        return saved
      }
    }
    val model = resolveModel(context)
    val path = model.getPath(context)
    if (File(path).exists()) {
      Log.i(TAG, "Model already present, skipping download: $path")
      return remember(context, path)
    }
    model.accessToken = RelaisConfig.hfToken(context)
    Log.i(TAG, "Model absent; downloading ${model.name} from ${model.url} -> $path")
    download(context, model, onProgress)
    require(File(path).exists()) { "Download reported success but file is missing: $path" }
    Log.i(TAG, "Model provisioned: $path")
    return remember(context, path)
  }

  /** Caches the resolved path in-memory and persists it for offline restarts. */
  private fun remember(context: Context, path: String): String {
    cachedPath = path
    RelaisConfig.setModelPath(context, path)
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
