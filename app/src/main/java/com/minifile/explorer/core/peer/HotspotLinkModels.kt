package com.minifile.explorer.core.peer

import com.minifile.explorer.core.model.JsonValue
import java.util.UUID

/**
 * Pure-JVM models for the BLE-negotiated hotspot link (spec §2.1–§2.3, `zero android.* imports`).
 * Every UUID / JSON key / flag below is shared byte-for-byte with the iOS implementation.
 */
object HotspotLinkUuids {
    /** "MFP1"…"minifi" primary service + scan filter (spec §2.1). */
    const val SERVICE = "4D465031-0001-4D46-8000-6D696E696669"
    const val INFO = "4D465031-0002-4D46-8000-6D696E696669"
    const val CTRL = "4D465031-0003-4D46-8000-6D696E696669"
    const val EVT = "4D465031-0004-4D46-8000-6D696E696669"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    val SERVICE_UUID: UUID = UUID.fromString(SERVICE)
    val INFO_UUID: UUID = UUID.fromString(INFO)
    val CTRL_UUID: UUID = UUID.fromString(CTRL)
    val EVT_UUID: UUID = UUID.fromString(EVT)
    val CCCD_UUID: UUID = UUID.fromString(CCCD)
}

/** Negotiated link mode (`link.mode`). */
enum class LinkMode(val wire: String) {
    LAN("lan"),

    /** Receiver (peripheral, Android) hosts the LocalOnlyHotspot. */
    LOHS_R("lohs-r"),

    /** Sender (central, Android) hosts; iOS receiver joins and `announce`s. */
    LOHS_S("lohs-s"),
    ;

    companion object {
        fun fromWire(raw: String?): LinkMode? = entries.firstOrNull { it.wire == raw?.trim() }
    }
}

/** Passive advertisement contents (Android `0xFFFF` SCAN_RSP or iOS `MF:<id6>:<name>` local name). */
data class BleAdvertisement(
    val platform: String,
    val id6: String,
    val name: String,
    val flags: Int,
) {
    val canHost: Boolean get() = flags and HotspotLinkCodec.FLAG_CAN_HOST != 0
    val receiving: Boolean get() = flags and HotspotLinkCodec.FLAG_RECEIVING != 0
    val busy: Boolean get() = flags and HotspotLinkCodec.FLAG_BUSY != 0
}

/** GATT `INFO` characteristic JSON (spec §2.2). */
data class BlePeerCard(
    val id: String,
    val name: String,
    val os: String,
    val ver: String,
    val caps: List<String>,
    val port: Int,
    val lan: List<String>,
    val v: Int = 1,
)

/** CTRL / EVT JSON messages keyed by `t` (spec §2.3). */
sealed class HotspotLinkMessage {
    abstract val type: String

    data class Hello(
        val id: String,
        val name: String,
        val os: String,
        val ver: String,
        val files: Int,
        val bytes: Long,
        val tok: String,
        val lan: List<String>,
        val v: Int = 1,
    ) : HotspotLinkMessage() {
        override val type: String get() = "hello"
    }

    data class Ack(
        val id: String,
        val name: String,
        val os: String,
        val caps: List<String>,
        val port: Int,
        val lan: List<String>,
    ) : HotspotLinkMessage() {
        override val type: String get() = "ack"
    }

    /** `reason` ∈ `user|busy|timeout|perm`. */
    data class Decline(val reason: String) : HotspotLinkMessage() {
        override val type: String get() = "decline"
    }

    data class Link(val mode: LinkMode) : HotspotLinkMessage() {
        override val type: String get() = "link"
    }

    /** `sec` ∈ `wpa2|wpa3t|wpa3`. */
    data class Hotspot(
        val ssid: String,
        val pass: String,
        val sec: String,
        val ip: String,
        val port: Int,
        val tok: String,
    ) : HotspotLinkMessage() {
        override val type: String get() = "hotspot"
    }

    data class Joined(val ip: String) : HotspotLinkMessage() {
        override val type: String get() = "joined"
    }

    data class Ready(val ip: String, val port: Int) : HotspotLinkMessage() {
        override val type: String get() = "ready"
    }

    data class Done(val ok: Boolean) : HotspotLinkMessage() {
        override val type: String get() = "done"
    }

    /** `code` ∈ `lohs_failed|join_failed|timeout|busy|perm|cancelled`. */
    data class Error(val code: String) : HotspotLinkMessage() {
        override val type: String get() = "error"
    }

    companion object {
        const val DECLINE_USER = "user"
        const val DECLINE_BUSY = "busy"
        const val DECLINE_TIMEOUT = "timeout"
        const val DECLINE_PERM = "perm"

        const val ERR_LOHS_FAILED = "lohs_failed"
        const val ERR_JOIN_FAILED = "join_failed"
        const val ERR_TIMEOUT = "timeout"
        const val ERR_BUSY = "busy"
        const val ERR_PERM = "perm"
        const val ERR_CANCELLED = "cancelled"
    }
}

/** Spec §2.3 step 3 decision table. */
object HostDecision {
    /**
     * @return the link mode the central (sender) writes, or `null` when the pair must never link over BLE
     * (iOS ↔ iOS uses AWDL instead).
     */
    fun decide(senderOs: String, receiverOs: String, lanOverlap: Boolean): LinkMode? {
        if (lanOverlap) return LinkMode.LAN
        val s = senderOs.lowercase()
        val r = receiverOs.lowercase()
        return when {
            r == HotspotLinkCodec.OS_ANDROID -> LinkMode.LOHS_R
            r == HotspotLinkCodec.OS_IOS && s == HotspotLinkCodec.OS_ANDROID -> LinkMode.LOHS_S
            else -> null
        }
    }

    /** `true` when any IPv4 of [a] shares a /24 with any IPv4 of [b]. */
    fun sharesSlash24(a: List<String>, b: List<String>): Boolean {
        val pa = a.mapNotNull(::slash24Prefix).toSet()
        if (pa.isEmpty()) return false
        return b.any { slash24Prefix(it) in pa }
    }

    internal fun slash24Prefix(ip: String): String? {
        val parts = ip.trim().split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}"
    }

    /** IPv4 CIDR membership on raw 4-byte addresses (`prefixLength` clamped to 0..32). */
    fun inSubnet(addr: ByteArray, base: ByteArray, prefixLength: Int): Boolean {
        if (addr.size != 4 || base.size != 4) return false
        val p = prefixLength.coerceIn(0, 32)
        if (p == 0) return true
        val a = ipv4ToInt(addr)
        val b = ipv4ToInt(base)
        val mask = if (p == 32) -1 else (-1 shl (32 - p))
        return (a and mask) == (b and mask)
    }

    fun inSubnet(ip: String, base: String, prefixLength: Int): Boolean {
        val a = parseIpv4(ip) ?: return false
        val b = parseIpv4(base) ?: return false
        return inSubnet(a, b, prefixLength)
    }

    fun parseIpv4(ip: String): ByteArray? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    private fun ipv4ToInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}

internal fun jsonStrList(items: List<String>): JsonValue.Arr = JsonValue.Arr(items.map { JsonValue.Str(it) })

internal fun JsonValue.Obj.strList(key: String): List<String> =
    arr(key)?.items?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()

/** Merged "Nearby Devices" list (spec §2.1 / §3.7): LAN peers first; BLE rows deduped by `id6` (LAN wins). */
object PeerMerge {
    fun merge(lan: List<PeerInfo>, ble: List<PeerInfo>): List<PeerInfo> {
        if (ble.isEmpty()) return lan
        val lanId6 = lan.map { HotspotLinkCodec.id6Of(it.id) }.toHashSet()
        val extra = ble.filter { HotspotLinkCodec.id6Of(it.id) !in lanId6 }
        return if (extra.isEmpty()) lan else lan + extra
    }
}
