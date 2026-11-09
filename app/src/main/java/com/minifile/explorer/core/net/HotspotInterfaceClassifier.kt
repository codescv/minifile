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
        "utun", "epdg", "sit", "ip6tnl", "dummy", "lo", "tailscale", "ims", "oem", "gre", "ip_vti", "ip6_vti",
        "seth", "nlmon", "vcan", "tunl", "erspan", "ifb",
    )

    fun isExcluded(interfaceName: String): Boolean {
        val n = interfaceName.trim().lowercase()
        return n.isEmpty() || EXCLUDED_PREFIXES.any { n.startsWith(it) }
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
     * @param trackedIps IPv4s of every network ConnectivityManager tracks (STA, Ethernet, cellular…).
     * @param before IPv4s present just before `startLocalOnlyHotspot` (`null` = no snapshot).
     */
    fun classify(
        current: List<IfaceIpv4>,
        trackedIps: Set<String>,
        before: Set<String>? = null,
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
            val prefix = a.prefixLength.takeIf { it in 8..30 } ?: 24
            out += Ranked(HotspotCandidate(name, a.ipv4, prefix), rank)
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
}
