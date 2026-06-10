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

private const val TAG = "RelaisDiscovery"
const val RELAIS_SERVICE_TYPE = "_relais._tcp."

/**
 * Zero-config LAN discovery via mDNS/NSD. Advertises the node as `_relais._tcp` so clients find it
 * by name instead of a hard-coded IP — fixes the IP-change problem the wifi soak surfaced.
 */
object RelaisDiscovery {
  private var nsdManager: NsdManager? = null
  private var listener: NsdManager.RegistrationListener? = null

  fun register(context: Context, httpPort: Int = 8080, httpsPort: Int = 8443) {
    if (listener != null) return
    val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val info =
      NsdServiceInfo().apply {
        serviceName = "relais-node"
        serviceType = RELAIS_SERVICE_TYPE
        port = httpPort
        setAttribute("model", "gemma-4-e4b-it")
        setAttribute("https", httpsPort.toString())
        setAttribute("api", "openai")
      }
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

  fun unregister() {
    listener?.let { runCatching { nsdManager?.unregisterService(it) } }
    listener = null
    nsdManager = null
  }
}
