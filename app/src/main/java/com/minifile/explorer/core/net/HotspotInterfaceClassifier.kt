package com.minifile.explorer.core.net

import com.minifile.explorer.core.peer.HostDecision

/** One IPv4 seen on a local interface (pure snapshot of `NetworkInterface` / `InterfaceAddress`). */
data class IfaceIpv4(
    val interfaceName: String,
    val ipv4: String,
    val prefixLength: Int,
)

/** IPv4 this device serves as a (LocalOnly)Hotspot / soft-AP gateway (spec §3.4). */
data class HotspotCandidate(
    val interfaceName: String,
    val ipv4: String,
    val prefixLength: Int,
)

/**
 * Pure LocalOnlyHotspot interface detection (spec §3.4, `zero android.* imports`).
 *
 * OEMs name the LOHS interface differently: AOSP/Pixel `wlan1` or `ap0`, Samsung `swlan0`, MediaTek `ap0`, and on
 * OnePlus/Qualcomm STA+AP concurrency `wlan1`/`wlan2` while the station stays on `wlan0`. With STA disconnected
 * some chips even bring the AP up on `wlan0`. Rules:
 * 1. An IPv4 owned by a ConnectivityManager-tracked network ([trackedIps], i.e. STA / Ethernet / cellular) is never
 *    the hotspot, nor is anything on an excluded WAN/tunnel interface.
 * 2. Soft-AP names (`ap*`, `swlan*`, `softap*`, `sap*`), secondary radios `wlan[1-9]*` and Wi-Fi Direct `p2p*`
 *    are candidates.
 * 3. When a pre-LOHS snapshot ([before]) is given, any **wireless** IPv4 (`wlan*` included) that did not exist
 *    before `startLocalOnlyHotspot` is a candidate too, and newly appeared addresses rank first.
 *
 * Ranking: newly appeared → soft-AP names → `wlan[1-9]*` → `p2p*`; then `*.1` first, then interface name.
 */
object HotspotInterfaceClassifier {
    private val WLAN_SECONDARY = Regex("^wlan[1-9][0-9]*$")

    /** Soft-AP names: AOSP `ap0`/`ap_br_wlan1` (bridged AP), Samsung `swlan0`, `softap0`, Qualcomm `sap0`, NXP `uap0`. */
    private val SOFTAP_PREFIXES = listOf("ap", "swlan", "softap", "sap", "uap")
    private val WIRELESS_PREFIXES = listOf("wlan", "ap", "swlan", "softap", "sap", "uap", "p2p", "wfd", "wifi")

    /** USB / Bluetooth / bridge tethering and Wi-Fi Direct interfaces that may serve a local subnet. */
    private val TETHER_PREFIXES = listOf("p2p", "wfd", "bridge", "rndis", "ncm", "usb", "bt-pan", "bnep")

    /** Every name prefix that can carry a peer-reachable LAN subnet (STA, soft-AP, tether, Ethernet). */
    private val LOCAL_LAN_PREFIXES = WIRELESS_PREFIXES + TETHER_PREFIXES + listOf("eth")

    private val EXCLUDED_PREFIXES = listOf(
        "rmnet", "r_rmnet", "ccmni", "pdp", "wwan", "tun", "tap", "ppp", "wg", "ipsec", "clat", "v4-",
        "utun", "epdg", "sit", "ip6tnl", "dummy", "tailscale", "ims", "oem", "gre", "ip_vti", "ip6_vti",
        "seth", "nlmon", "vcan", "tunl", "erspan", "ifb",
    )

    /** Loopback is matched exactly (`lo`, `lo0`…): a bare `lo` prefix would also hide any `lo*`-named soft-AP. */
    private val LOOPBACK = Regex("^lo[0-9]*$")

    fun isExcluded(interfaceName: String): Boolean {
        val n = interfaceName.trim().lowercase()
        return n.isEmpty() || LOOPBACK.matches(n) || EXCLUDED_PREFIXES.any { n.startsWith(it) }
    }

    fun isSoftApName(interfaceName: String): Boolean {
        val n = interfaceName.trim().lowercase()
        return SOFTAP_PREFIXES.any { n.startsWith(it) }
    }

    fun isSecondaryWlan(interfaceName: String): Boolean = WLAN_SECONDARY.matches(interfaceName.trim().lowercase())

    fun isWireless(interfaceName: String): Boolean {
        val n = interfaceName.trim().lowercase()
        return WIRELESS_PREFIXES.any { n.startsWith(it) }
    }

    /**
     * System Personal Hotspot / LOHS / tethering style interface (`ap*`, `ap_br_*`, `swlan*`, `softap*`, `sap*`,
     * `uap*`, `wlan[1-9]*`, `p2p*`, `wfd*`, `bridge*`, `rndis*`, `ncm*`, `usb*`). Never cellular / VPN / loopback.
     * `wlan0` itself is **not** included by name (it is usually the station); callers detect a standalone soft-AP on
     * `wlan0` by it being absent from every tracked network.
     */
    fun isHotspotOrTetherName(interfaceName: String): Boolean {
        if (isExcluded(interfaceName)) return false
        val n = interfaceName.trim().lowercase()
        return isSoftApName(n) || isSecondaryWlan(n) || TETHER_PREFIXES.any { n.startsWith(it) }
    }

    /** Any interface that may carry a peer-reachable local subnet (Wi-Fi STA / soft-AP / tether / Ethernet). */
    fun isLocalLanName(interfaceName: String): Boolean {
        if (isExcluded(interfaceName)) return false
        val n = interfaceName.trim().lowercase()
        return LOCAL_LAN_PREFIXES.any { n.startsWith(it) }
    }

    /** Spec §3.4: interface names that can carry a LocalOnlyHotspot / soft-AP gateway address by name alone. */
    fun isHotspotInterfaceName(interfaceName: String): Boolean {
        if (isExcluded(interfaceName)) return false
        val n = interfaceName.trim().lowercase()
        return isSoftApName(n) || isSecondaryWlan(n) || n.startsWith("p2p")
    }

    /**
     * @param current every up, non-loopback IPv4 now (already filtered to LAN-range addresses by the caller).
     * @param trackedIps IPv4s of every **station-like** network ConnectivityManager tracks (STA, Ethernet, cellular…).
     *   Networks the device itself serves (`NET_CAPABILITY_LOCAL_NETWORK`, e.g. the Android 15+ tethering local
     *   network agent for LOHS) must NOT be included — see [stationIps].
     * @param before IPv4s present just before `startLocalOnlyHotspot` (`null` = no snapshot).
     * @param beforeIfaces interface names that carried an IPv4 just before `startLocalOnlyHotspot` (`null` = unknown).
     *   Enables the relaxed pass: when the strict rules find nothing, a NEW IPv4 on an interface that had no address
     *   before LOHS started is the hotspot even if ConnectivityManager tracks it or its name is unusual.
     */
    fun classify(
        current: List<IfaceIpv4>,
        trackedIps: Set<String>,
        before: Set<String>? = null,
        beforeIfaces: Set<String>? = null,
    ): List<HotspotCandidate> {
        data class Ranked(val c: HotspotCandidate, val rank: Int)
        val out = ArrayList<Ranked>()
        for (a in current) {
            val name = a.interfaceName
            if (isExcluded(name)) continue
            if (HostDecision.parseIpv4(a.ipv4) == null) continue
            if (a.ipv4 in trackedIps) continue
            val isNew = before != null && a.ipv4 !in before && isWireless(name)
            val n = name.trim().lowercase()
            val rank = when {
                isNew -> 0
                isSoftApName(n) -> 1
                isSecondaryWlan(n) -> 2
                n.startsWith("p2p") -> 4
                else -> continue
            }
            out += Ranked(HotspotCandidate(name, a.ipv4, sanePrefix(a.prefixLength)), rank)
        }
        if (out.isEmpty() && before != null && beforeIfaces != null) {
            val oldIfaces = beforeIfaces.mapTo(HashSet()) { it.trim().lowercase() }
            for (a in current) {
                val name = a.interfaceName
                val n = name.trim().lowercase()
                if (isExcluded(name) || HostDecision.parseIpv4(a.ipv4) == null) continue
                if (a.ipv4 in before || n in oldIfaces) continue
                val rank = when {
                    isSoftApName(n) || isSecondaryWlan(n) -> 10
                    isWireless(n) -> 11
                    else -> 12
                }
                out += Ranked(HotspotCandidate(name, a.ipv4, sanePrefix(a.prefixLength)), rank)
            }
        }
        return out
            .sortedWith(
                compareBy<Ranked> { it.rank }
                    .thenBy { if (it.c.ipv4.endsWith(".1")) 0 else 1 }
                    .thenBy { it.c.interfaceName },
            )
            .map { it.c }
            .distinctBy { it.ipv4 }
    }

    /**
     * The local hotspot address that shares a subnet with [peerIp] — used when a joined peer reports its address
     * (BLE `ready`, announce) before interface classification found the LOHS. The match must be an address that
     * did NOT exist before LOHS started ([before]), so a peer still on the station Wi-Fi (`100.74.206.81` inside
     * `wlan0 100.74.207.102/21`) never turns the station into the "hotspot". Longest prefix wins.
     */
    fun matchPeerSubnet(peerIp: String, current: List<IfaceIpv4>, before: Set<String>?): HotspotCandidate? {
        val peer = HostDecision.parseIpv4(peerIp) ?: return null
        return current
            .asSequence()
            .filter { !isExcluded(it.interfaceName) && it.ipv4 != peerIp }
            .filter { before == null || it.ipv4 !in before }
            .mapNotNull { a -> HostDecision.parseIpv4(a.ipv4)?.let { a to it } }
            .filter { (a, ip) -> HostDecision.inSubnet(peer, ip, sanePrefix(a.prefixLength)) }
            .maxByOrNull { (a, _) -> sanePrefix(a.prefixLength) }
            ?.let { (a, _) -> HotspotCandidate(a.interfaceName, a.ipv4, sanePrefix(a.prefixLength)) }
    }

    /** One ConnectivityManager network as seen by [stationIps]. */
    data class TrackedNetIps(
        val interfaceName: String?,
        val ipv4s: List<String>,
        /** `NET_CAPABILITY_LOCAL_NETWORK` (API 35+): a network this device serves (LOHS / tethering downstream). */
        val isLocalNetwork: Boolean,
    )

    /**
     * IPv4s of tracked networks that are stations (STA / Ethernet / cellular). Android 15+ registers the LOHS
     * downstream as a `NET_CAPABILITY_LOCAL_NETWORK` network; counting its gateway address as "tracked" hid the
     * hotspot IP forever (OnePlus PKX110 / Android 17: `hostIp=?` for the whole session).
     */
    fun stationIps(nets: List<TrackedNetIps>): Set<String> =
        nets.filter { !it.isLocalNetwork }.flatMapTo(HashSet()) { it.ipv4s }

    /**
     * Diagnostic one-liner of every up IPv4 (`wlan0 100.74.207.102/21 sta, wlan2 10.122.99.203/24 local new`).
     * Flags: `sta` = tracked station IP, `local` = `NET_CAPABILITY_LOCAL_NETWORK` IP, `new` = absent before LOHS,
     * `excl` = excluded interface (cellular / VPN / loopback…).
     */
    fun describe(
        all: List<IfaceIpv4>,
        stationIps: Set<String>,
        localNetworkIps: Set<String> = emptySet(),
        before: Set<String>? = null,
    ): String {
        if (all.isEmpty()) return "[]"
        return all.joinToString(prefix = "[", postfix = "]", separator = ", ") { a ->
            val flags = buildList {
                if (a.ipv4 in stationIps) add("sta")
                if (a.ipv4 in localNetworkIps) add("local")
                if (before != null && a.ipv4 !in before) add("new")
                if (isExcluded(a.interfaceName)) add("excl")
            }
            "${a.interfaceName} ${a.ipv4}/${a.prefixLength}" + if (flags.isEmpty()) "" else " " + flags.joinToString(" ")
        }
    }

    private fun sanePrefix(p: Int): Int = p.takeIf { it in 8..30 } ?: 24
}
