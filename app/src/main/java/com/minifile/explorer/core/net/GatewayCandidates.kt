package com.minifile.explorer.core.net

import com.minifile.explorer.core.peer.HostDecision

/**
 * Pure-JVM candidate hosts for the automatic hotspot-gateway prober (`zero android.* imports`).
 *
 * After the system Camera joins a phone hotspot from a `WIFI:` QR (iPhone Personal Hotspot `172.20.10.1/28`,
 * Android hotspot `192.168.43.1/24`, LOHS / Wi-Fi Direct `192.168.49.1/24`), the hosting phone is the Wi-Fi gateway.
 * Files probes these addresses for `GET /api/peer/info` so the host pops into Nearby Devices with zero typing.
 */
object GatewayCandidates {
    /** Ports always probed on a candidate gateway. */
    val BASE_PORTS: List<Int> = listOf(8080, 8081, 8082)

    /** Extra ports probed when the Wi-Fi looks like a phone hotspot. */
    val HOTSPOT_EXTRA_PORTS: List<Int> = (8083..8090).toList()

    /**
     * Ordered, de-duplicated gateway candidates: the route gateway first, then the subnet network address `+ 1`, and
     * for the iPhone Personal Hotspot range (`172.20.10.0/24`, served in `/28` DHCP blocks) also `172.20.10.1`.
     * Excludes [ownIpv4], loopback, `0.0.0.0`, the subnet broadcast address and anything unparsable.
     */
    fun forWifi(ownIpv4: String?, prefixLen: Int, routeGatewayIpv4: String?): List<String> {
        val own = ownIpv4?.let { HostDecision.parseIpv4(it) }
        val out = LinkedHashSet<String>()
        routeGatewayIpv4?.let { HostDecision.parseIpv4(it) }?.let { out.add(fmt(it)) }
        if (own != null) {
            val p = if (prefixLen in 8..30) prefixLen else 24
            val mask = if (p == 0) 0 else (-1 shl (32 - p))
            val ownInt = toInt(own)
            out.add(fmt(fromInt((ownInt and mask) or 1)))
            if (isIphoneHotspotRange(own)) out.add("172.20.10.1")
        }
        val ownStr = own?.let(::fmt)
        val broadcast = own?.let {
            val p = if (prefixLen in 8..30) prefixLen else 24
            fmt(fromInt(toInt(it) or ((1 shl (32 - p)) - 1)))
        }
        return out.filter { c ->
            val b = HostDecision.parseIpv4(c) ?: return@filter false
            val first = b[0].toInt() and 0xFF
            c != ownStr && c != broadcast && c != "0.0.0.0" && first != 127 && first != 0 && first < 224
        }
    }

    /** `true` for the iPhone Personal Hotspot range `172.20.10.0/24`. */
    fun isIphoneHotspotRange(ip: ByteArray): Boolean =
        ip.size == 4 && (ip[0].toInt() and 0xFF) == 172 && (ip[1].toInt() and 0xFF) == 20 && (ip[2].toInt() and 0xFF) == 10

    /**
     * `true` when this Wi-Fi looks like a phone hotspot: iPhone `172.20.10.x`, Android `192.168.43.x`, LOHS / Wi-Fi
     * Direct `192.168.49.x`, OnePlus / ColorOS / MIUI system hotspot `192.168.191.x`, or the network is metered
     * (Android marks phone hotspots metered).
     */
    fun looksLikePhoneHotspot(ownIpv4: String?, metered: Boolean): Boolean {
        if (metered) return true
        val b = ownIpv4?.let { HostDecision.parseIpv4(it) } ?: return false
        if (isIphoneHotspotRange(b)) return true
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        val a2 = b[2].toInt() and 0xFF
        return a0 == 192 && a1 == 168 && (a2 == 43 || a2 == 49 || a2 == 191)
    }

    /** [BASE_PORTS] plus [HOTSPOT_EXTRA_PORTS] for phone hotspots, with [preferredPort] (if valid) first. */
    fun probePorts(ownIpv4: String?, metered: Boolean, preferredPort: Int? = null): List<Int> {
        val out = LinkedHashSet<Int>()
        if (preferredPort != null && preferredPort in 1..65535) out.add(preferredPort)
        out.addAll(BASE_PORTS)
        if (looksLikePhoneHotspot(ownIpv4, metered)) out.addAll(HOTSPOT_EXTRA_PORTS)
        return out.toList()
    }

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun fromInt(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun fmt(b: ByteArray): String = b.joinToString(".") { (it.toInt() and 0xFF).toString() }
}
