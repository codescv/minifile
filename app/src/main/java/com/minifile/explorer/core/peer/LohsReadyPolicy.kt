package com.minifile.explorer.core.peer

/** What `lohs-s` does with one BLE `EVT ready` from the joining receiver (see [LohsReadyPolicy]). */
sealed interface ReadyAction {
    /** Probe `ip:port` (cancelling any in-flight probe of a different endpoint). */
    data class Probe(val ip: String, val port: Int) : ReadyAction {
        val key: String get() = LohsReadyPolicy.key(ip, port)
    }

    /** Unusable endpoint (bad port / not a unicast IPv4 / our own hotspot address): skip, keep listening. */
    data object BadEndpoint : ReadyAction

    /** The hotspot address is known and `ip` is not on its subnet (e.g. the receiver's old station IP): skip. */
    data object OffHotspotSubnet : ReadyAction

    /** The very same endpoint is already being probed: let that probe finish. */
    data object AlreadyProbing : ReadyAction
}

/**
 * Pure `lohs-s` BLE `ready` handling (`zero android.* imports`).
 *
 * Real-device bug (OnePlus PKX110 / Android 17 ↔ iPhone 17): iOS first sent `ready ip=<old corp Wi-Fi IP>`, Android
 * probed it 5× and then **stopped listening**, so the real `ready ip=10.122.99.178` 50 s later was dropped. Every
 * `ready` must be evaluated until one probe succeeds or the session ends; a different endpoint supersedes the
 * in-flight probe.
 */
object LohsReadyPolicy {

    fun key(ip: String, port: Int): String = "$ip:$port"

    /**
     * @param hostIp this device's LOHS address (`""` = still unknown: every unicast IPv4 is probed).
     * @param inFlightKey [key] of the probe still running, `null` when none is running.
     */
    fun decide(ip: String, port: Int, hostIp: String, prefixLength: Int, inFlightKey: String?): ReadyAction {
        if (port !in 1..65535) return ReadyAction.BadEndpoint
        val addr = HostDecision.parseIpv4(ip) ?: return ReadyAction.BadEndpoint
        val a = addr[0].toInt() and 0xFF
        val allZero = addr.all { it.toInt() == 0 }
        if (allZero || a == 127 || a >= 224 || ip.endsWith(".255")) return ReadyAction.BadEndpoint
        if (hostIp.isNotEmpty()) {
            if (ip == hostIp) return ReadyAction.BadEndpoint
            if (!HostDecision.inSubnet(ip, hostIp, prefixLength.takeIf { it in 8..30 } ?: 24)) {
                return ReadyAction.OffHotspotSubnet
            }
        }
        if (inFlightKey == key(ip, port)) return ReadyAction.AlreadyProbing
        return ReadyAction.Probe(ip, port)
    }
}
