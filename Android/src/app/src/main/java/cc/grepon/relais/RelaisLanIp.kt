/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LAN IPv4 discovery for the `/v1/clientconfig` base URL (extracted from `RelaisHttpServer` — #173).
 * No UI/Context dependency, so it's a pure network-interface helper.
 */
internal object RelaisLanIp {

  /**
   * The LAN IPv4 to advertise. Prefers the accepting socket's local address (the exact interface this
   * connection arrived on — the most accurate value for the URL the client should call back). Falls
   * back to [lanIpv4] only if that address is missing, loopback, a wildcard, or non-IPv4 (e.g. the
   * HTTPS listener bound to 0.0.0.0).
   */
  fun localLanIp(sock: java.net.Socket): String {
    val local = sock.localAddress
    if (local is Inet4Address && !local.isLoopbackAddress && !local.isAnyLocalAddress) {
      local.hostAddress?.let { return it }
    }
    return lanIpv4()
  }

  /**
   * Best-effort LAN IPv4 (prefers wlan), used only as a fallback when the accepting socket's local
   * address is unavailable/loopback/wildcard. Kept small + UI-free so the server has no UI dependency.
   * Returns "0.0.0.0" if nothing resolves.
   */
  fun lanIpv4(): String =
    runCatching {
      val nis = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
      val ordered = nis.sortedByDescending { it.name.startsWith("wlan") }
      for (ni in ordered) {
        for (addr in ni.inetAddresses) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) return@runCatching addr.hostAddress ?: continue
        }
      }
      "0.0.0.0"
    }.getOrDefault("0.0.0.0")
}
