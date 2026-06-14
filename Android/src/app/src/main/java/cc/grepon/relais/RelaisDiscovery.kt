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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import cc.grepon.relais.BuildConfig

private const val TAG = "RelaisDiscovery"
const val RELAIS_SERVICE_TYPE = "_relais._tcp."

/**
 * Zero-config LAN discovery via mDNS/NSD. Advertises the node as `_relais._tcp` so clients find it
 * by name instead of a hard-coded IP — fixes the IP-change problem the wifi soak surfaced.
 */
object RelaisDiscovery {
  private var nsdManager: NsdManager? = null
  private var listener: NsdManager.RegistrationListener? = null
  private val lock = Any()

  /**
   * Snapshots the live capabilities for the TXT record. `tools` + `reasoning` are node-level (always
   * supported via the native LiteRT-LM API); `multimodal` is model-dependent and read from the
   * engine's truthful [RelaisEngine.isMultimodal] flag.
   */
  private fun liveCaps(): RelaisClientConfig.Capabilities =
    RelaisClientConfig.Capabilities(
      multimodal = RelaisEngine.isMultimodal,
      tools = true,
      reasoning = true,
    )

  /**
   * Builds the [NsdServiceInfo] with a dynamic TXT record reflecting the live model, app version,
   * and capabilities (via [RelaisClientConfig.buildDiscoveryTxt]). The TXT is cleartext LAN
   * broadcast — it carries only routing metadata, never the API key.
   */
  private fun buildServiceInfo(context: Context, httpPort: Int, httpsPort: Int): NsdServiceInfo {
    val txt =
      RelaisClientConfig.buildDiscoveryTxt(
        modelId = RelaisConfig.modelId(context),
        version = BuildConfig.VERSION_NAME,
        httpsPort = httpsPort,
        caps = liveCaps(),
      )
    return NsdServiceInfo().apply {
      serviceName = "relais-node"
      serviceType = RELAIS_SERVICE_TYPE
      port = httpPort
      txt.forEach { (k, v) -> setAttribute(k, v) }
    }
  }

  fun register(context: Context, httpPort: Int = 8080, httpsPort: Int = 8443) {
    synchronized(lock) {
      if (listener != null) return
      val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
      val info = buildServiceInfo(context, httpPort, httpsPort)
      val l =
        object : NsdManager.RegistrationListener {
          override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS registered: ${info.serviceName}.$RELAIS_SERVICE_TYPE port ${info.port}")
          }

          override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS registration failed: $errorCode")
          }

          override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "mDNS unregistered")
          }

          override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS unregistration failed: $errorCode")
          }
        }
      manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
      nsdManager = manager
      listener = l
    }
  }

  /**
   * Re-publishes the TXT record after the live model (or its capabilities) changed. NSD has no
   * portable in-place TXT update, so this unregisters then re-registers with a freshly-built record.
   * Re-entrancy is guarded by [lock]; a no-op if the service isn't currently registered (the next
   * [register] picks up the live values anyway).
   *
   * Integration point: an in-app model switch currently requires a process restart ("Restart to
   * apply" in RelaisControlActivity), and restart re-registers via [register] — so the TXT is always
   * fresh after a switch. Call this from any future hot-swap path that changes the model without a
   * restart.
   */
  fun updateModel(context: Context, httpPort: Int = 8080, httpsPort: Int = 8443) {
    synchronized(lock) {
      val manager = nsdManager
      val current = listener
      if (manager == null || current == null) return // not registered; nothing to refresh
      runCatching { manager.unregisterService(current) }
      listener = null
      nsdManager = null
    }
    register(context, httpPort, httpsPort)
  }

  fun unregister() {
    synchronized(lock) {
      listener?.let { runCatching { nsdManager?.unregisterService(it) } }
      listener = null
      nsdManager = null
    }
  }
}
