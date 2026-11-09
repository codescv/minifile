package com.minifile.explorer.core.net

import com.minifile.explorer.core.peer.HostDecision

/**
 * IPv4 subnet owned by a `ConnectivityManager`-tracked network (Wi-Fi STA / Ethernet `LinkProperties`).
 * [handleId] is `Network.getNetworkHandle()`; binding through it sets the kernel fwmark (`explicitlySelected`), which
 * picks the exact interface **and** skips the `tun0` VPN uid rule.
 */
class TrackedNetSubnet(
    val handleId: Long,
    val ifaceName: String?,
    val ipv4: ByteArray,
    val prefixLength: Int,
    val isWifiOrEth: Boolean = true,
) {
    override fun equals(other: Any?): Boolean = other is TrackedNetSubnet && other.handleId == handleId &&
        other.ifaceName == ifaceName && other.ipv4.contentEquals(ipv4) && other.prefixLength == prefixLength &&
        other.isWifiOrEth == isWifiOrEth

    override fun hashCode(): Int = (handleId.hashCode() * 31 + ipv4.contentHashCode()) * 31 + prefixLength

    override fun toString(): String = "TrackedNetSubnet($handleId, $ifaceName, ${LocalSubnetRouter.fmt(ipv4)}/$prefixLength)"
}

/**
 * Live IPv4 on a local interface from `NetworkInterface` (System Personal Hotspot `wlan1`/`ap0`/`ap_br_*`, LOHS,
 * USB tether `rndis0`, Wi-Fi Direct `p2p0`, or the STA `wlan0`). [isHotspotOrTetherLike] marks soft-AP / tether names.
 */
class LocalIfaceSubnet(
    val ifaceName: String,
    val ipv4: ByteArray,
    val prefixLength: Int,
    val isHotspotOrTetherLike: Boolean = HotspotInterfaceClassifier.isHotspotOrTetherName(ifaceName),
) {
    override fun equals(other: Any?): Boolean = other is LocalIfaceSubnet && other.ifaceName == ifaceName &&
        other.ipv4.contentEquals(ipv4) && other.prefixLength == prefixLength &&
        other.isHotspotOrTetherLike == isHotspotOrTetherLike

    override fun hashCode(): Int = (ifaceName.hashCode() * 31 + ipv4.contentHashCode()) * 31 + prefixLength

    override fun toString(): String = "LocalIfaceSubnet($ifaceName, ${LocalSubnetRouter.fmt(ipv4)}/$prefixLength)"
}

/** How an outbound peer TCP socket must be bound before `connect()`. */
sealed interface TcpBindDecision {
    /** A `WifiNetworkSpecifier` hotspot-client session is active: bind its pinned `Network`. */
    data object UsePinnedNetwork : TcpBindDecision

    /** `Network.bindSocket` on the tracked network with this handle (exact interface + VPN bypass). */
    data class BindTrackedNetwork(val handleId: Long) : TcpBindDecision

    /**
     * `socket.bind(localIpv4:0)` on an untracked local interface (system soft-AP / LOHS / tether): follows the
     * `local_network` route to that interface and never touches `wlan0`.
     */
    class BindLocalSourceIp(val localIpv4: ByteArray, val ifaceName: String) : TcpBindDecision {
        override fun equals(other: Any?): Boolean =
            other is BindLocalSourceIp && other.localIpv4.contentEquals(localIpv4) && other.ifaceName == ifaceName

        override fun hashCode(): Int = localIpv4.contentHashCode() * 31 + ifaceName.hashCode()

        override fun toString(): String = "BindLocalSourceIp(${LocalSubnetRouter.fmt(localIpv4)}, $ifaceName)"
    }

    /** Leave the socket unbound (loopback, or nothing better known). */
    data object NoExplicitBind : TcpBindDecision
}

/** How the discovery UDP socket must be bound. */
enum class UdpBindDecision {
    USE_PINNED_NETWORK,
    BIND_ACTIVE_WIFI,

    /** Stay unbound so `MFP1` broadcasts also leave via soft-AP / tether interfaces. */
    NO_EXPLICIT_BIND,
}

/**
 * Pure-JVM outbound socket routing for peer traffic (`zero android.* imports`).
 *
 * Fixes "iPhone returned HTTP 302": with STA+AP concurrency (OnePlus/Qualcomm) the user's manually enabled System
 * Personal Hotspot (`wlan1 = 192.168.43.1/24`, untracked by `ConnectivityManager`) coexists with the station
 * `wlan0` (`192.168.1.x`, captive portal / proxy). Binding a socket for `192.168.43.50` to the `wlan0` `Network`
 * shot it into `wlan0`'s gateway, which answered `302 Found`. Decision order of [decideTcp]:
 * 1. loopback → [TcpBindDecision.NoExplicitBind]
 * 2. pinned specifier network → [TcpBindDecision.UsePinnedNetwork]
 * 3. longest-prefix match among tracked Wi-Fi/Ethernet subnets → [TcpBindDecision.BindTrackedNetwork]
 * 4. LOHS subnet or longest-prefix match among live local interfaces → [TcpBindDecision.BindLocalSourceIp]
 *    (or the tracked network owning that interface name) — never the unrelated `wlan0`
 * 5. no local subnet matched (routed multi-VLAN LAN): bind the active Wi-Fi when its `LinkProperties` are unknown
 *    or the target is private/CGNAT/link-local; else [TcpBindDecision.NoExplicitBind].
 */
object LocalSubnetRouter {

    fun decideTcp(
        targetIpv4: ByteArray,
        hasPinnedNetwork: Boolean,
        localHotspotSubnet: LocalIfaceSubnet?,
        trackedSubnets: List<TrackedNetSubnet>,
        localIfaces: List<LocalIfaceSubnet>,
        fallbackActiveWifiHandle: Long?,
        activeWifiSubnets: List<TrackedNetSubnet>,
    ): TcpBindDecision {
        if (targetIpv4.size != 4) return TcpBindDecision.NoExplicitBind
        if ((targetIpv4[0].toInt() and 0xFF) == 127) return TcpBindDecision.NoExplicitBind
        if (hasPinnedNetwork) return TcpBindDecision.UsePinnedNetwork

        // 3. Tracked Wi-Fi / Ethernet network that owns the target subnet (longest prefix wins).
        trackedSubnets
            .filter { it.isWifiOrEth && it.ipv4.size == 4 && HostDecision.inSubnet(targetIpv4, it.ipv4, it.prefixLength) }
            .maxByOrNull { it.prefixLength }
            ?.let { return TcpBindDecision.BindTrackedNetwork(it.handleId) }

        // 4. Untracked local interface (LOHS, System Personal Hotspot, USB tether, Wi-Fi Direct).
        val hs = localHotspotSubnet?.takeIf {
            it.ipv4.size == 4 && HostDecision.inSubnet(targetIpv4, it.ipv4, sanePrefix(it.prefixLength))
        }
        val iface = hs ?: localIfaces
            .filter { it.ipv4.size == 4 && HostDecision.inSubnet(targetIpv4, it.ipv4, sanePrefix(it.prefixLength)) }
            .maxByOrNull { sanePrefix(it.prefixLength) }
        if (iface != null) {
            // Interface is actually a tracked network whose LinkProperties carry no matching prefix yet: prefer its
            // Network (VPN bypass) — but only for the very same interface, never a different `wlan0`.
            val owner = trackedSubnets.firstOrNull {
                it.isWifiOrEth && it.ifaceName != null && it.ifaceName == iface.ifaceName
            }
            return if (owner != null) {
                TcpBindDecision.BindTrackedNetwork(owner.handleId)
            } else {
                TcpBindDecision.BindLocalSourceIp(iface.ipv4.copyOf(), iface.ifaceName)
            }
        }

        // 5. Routed target outside every local subnet.
        if (fallbackActiveWifiHandle != null &&
            (activeWifiSubnets.isEmpty() || isPrivateLike(targetIpv4))
        ) {
            return TcpBindDecision.BindTrackedNetwork(fallbackActiveWifiHandle)
        }
        return TcpBindDecision.NoExplicitBind
    }

    /**
     * UDP discovery socket: binding it to the `wlan0` `Network` would confine `MFP1` broadcasts / beacon replies to
     * `wlan0`, hiding peers on a System Personal Hotspot / LOHS / tether. Stay unbound whenever such an untracked
     * interface exists outside the active Wi-Fi's interface.
     */
    fun decideUdp(
        hasPinnedNetwork: Boolean,
        hostingLocalHotspot: Boolean,
        hasActiveWifi: Boolean,
        activeWifiIfaceName: String?,
        trackedSubnets: List<TrackedNetSubnet>,
        localIfaces: List<LocalIfaceSubnet>,
    ): UdpBindDecision {
        if (hasPinnedNetwork) return UdpBindDecision.USE_PINNED_NETWORK
        if (hostingLocalHotspot) return UdpBindDecision.NO_EXPLICIT_BIND
        if (!hasActiveWifi) return UdpBindDecision.NO_EXPLICIT_BIND
        if (untrackedLocalIfaces(activeWifiIfaceName, trackedSubnets, localIfaces).isNotEmpty()) {
            return UdpBindDecision.NO_EXPLICIT_BIND
        }
        return UdpBindDecision.BIND_ACTIVE_WIFI
    }

    /** Local interfaces not owned by any tracked network (by name or address) and not the active Wi-Fi interface. */
    fun untrackedLocalIfaces(
        activeWifiIfaceName: String?,
        trackedSubnets: List<TrackedNetSubnet>,
        localIfaces: List<LocalIfaceSubnet>,
    ): List<LocalIfaceSubnet> {
        val trackedNames = trackedSubnets.mapNotNull { it.ifaceName }.toHashSet()
        return localIfaces.filter { li ->
            li.ifaceName != activeWifiIfaceName &&
                li.ifaceName !in trackedNames &&
                trackedSubnets.none { it.ipv4.contentEquals(li.ipv4) }
        }
    }

    /** `true` when [hostIpv4] lies inside any of [subnets] (`(ip, prefix)` pairs). */
    fun isOnAnyLocalSubnet(hostIpv4: ByteArray, subnets: List<Pair<ByteArray, Int>>): Boolean =
        hostIpv4.size == 4 && subnets.any { (ip, prefix) -> HostDecision.inSubnet(hostIpv4, ip, sanePrefix(prefix)) }

    /** RFC 1918, CGNAT `100.64/10` or link-local `169.254/16`. */
    fun isPrivateLike(ip: ByteArray): Boolean {
        if (ip.size != 4) return false
        val a = ip[0].toInt() and 0xFF
        val b = ip[1].toInt() and 0xFF
        return a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254)
    }

    /** Interface prefix lengths outside `1..32` (bogus `InterfaceAddress`) are treated as `/24`. */
    internal fun sanePrefix(p: Int): Int = if (p in 1..32) p else 24

    internal fun fmt(ip: ByteArray): String = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
}
