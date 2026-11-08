package com.minifile.explorer.core.peer

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Pure-JVM codecs for the BLE hotspot negotiation (spec §2.1–§2.3, §6; `zero android.* imports`):
 * passive advertisement payloads, app-level `[hdr][payload]` framing + reassembly, JSON messages,
 * and 128-bit session tokens.
 */
object HotspotLinkCodec {
    const val OS_ANDROID = "android"
    const val OS_IOS = "ios"

    /** Reserved/test Bluetooth SIG company id used for the Android SCAN_RSP manufacturer data. */
    const val MANUFACTURER_ID = 0xFFFF
    const val ADV_VERSION = 0x01

    const val FLAG_ANDROID = 0x01
    const val FLAG_CAN_HOST = 0x02
    const val FLAG_RECEIVING = 0x04
    const val FLAG_BUSY = 0x08

    const val ID6_LEN = 6

    /** SCAN_RSP manufacturer payload budget: `[ver][flags][id6][name ≤ 19 B]` = 27 B. */
    const val MAX_ANDROID_NAME_BYTES = 19

    /** iOS local name `MF:<id6>:<name>` is ≤ 22 B total ⇒ name ≤ 12 B. */
    const val MAX_IOS_LOCAL_NAME_BYTES = 22
    const val IOS_NAME_PREFIX = "MF:"

    const val MAX_MESSAGE_BYTES = 2048
    const val MIN_CHUNK = 19
    const val MAX_CHUNK = 240
    const val HDR_LAST = 0x80
    const val SEQ_MASK = 0x7F

    const val TOKEN_HEX_LEN = 32

    // ---------------------------------------------------------------------------------------------
    // Passive advertisement (spec §2.1)
    // ---------------------------------------------------------------------------------------------

    /** First 6 chars of the peer device id, ASCII-only (non-ASCII → `_`), right-padded with `0`. */
    fun id6Of(deviceId: String): String {
        val sb = StringBuilder(ID6_LEN)
        for (ch in deviceId) {
            if (sb.length >= ID6_LEN) break
            sb.append(if (ch.code in 0x21..0x7E && ch != ':') ch else '_')
        }
        while (sb.length < ID6_LEN) sb.append('0')
        return sb.toString()
    }

    /** Android SCAN_RSP manufacturer-specific payload (without the 2-byte company id). */
    fun encodeAndroidManufacturerData(deviceId: String, name: String, flags: Int): ByteArray {
        val out = ByteArrayOutputStream(27)
        out.write(ADV_VERSION)
        out.write(flags and 0xFF)
        out.write(id6Of(deviceId).toByteArray(Charsets.US_ASCII))
        out.write(truncateUtf8(cleanName(name), MAX_ANDROID_NAME_BYTES))
        return out.toByteArray()
    }

    fun parseManufacturerData(data: ByteArray?): BleAdvertisement? {
        if (data == null || data.size < 2 + ID6_LEN) return null
        if ((data[0].toInt() and 0xFF) != ADV_VERSION) return null
        val flags = data[1].toInt() and 0xFF
        val id6 = String(data, 2, ID6_LEN, Charsets.US_ASCII)
        if (id6.any { it.code !in 0x21..0x7E }) return null
        val name = if (data.size > 2 + ID6_LEN) {
            decodeUtf8Lenient(data.copyOfRange(2 + ID6_LEN, data.size))
        } else {
            ""
        }
        return BleAdvertisement(platform = OS_ANDROID, id6 = id6, name = name, flags = flags)
    }

    /** iOS `CBAdvertisementDataLocalNameKey` value `MF:<id6>:<name>` (≤ 22 B, cut at a char boundary). */
    fun encodeIosLocalName(deviceId: String, name: String): String {
        val head = "$IOS_NAME_PREFIX${id6Of(deviceId)}:"
        val budget = MAX_IOS_LOCAL_NAME_BYTES - head.toByteArray(Charsets.UTF_8).size
        return head + decodeUtf8Lenient(truncateUtf8(cleanName(name), budget))
    }

    fun parseIosLocalName(localName: String?): BleAdvertisement? {
        if (localName == null || !localName.startsWith(IOS_NAME_PREFIX)) return null
        val rest = localName.substring(IOS_NAME_PREFIX.length)
        if (rest.length < ID6_LEN + 1 || rest[ID6_LEN] != ':') return null
        val id6 = rest.substring(0, ID6_LEN)
        if (id6.any { it.code !in 0x21..0x7E }) return null
        return BleAdvertisement(
            platform = OS_IOS,
            id6 = id6,
            name = rest.substring(ID6_LEN + 1),
            flags = FLAG_RECEIVING,
        )
    }

    /**
     * Parses a raw legacy advertisement / scan-response byte blob (AD structures). Prefers the Android
     * `0xFFFF` manufacturer payload, else the iOS `MF:` local name. Returns `null` for foreign devices.
     */
    fun parseScanRecord(raw: ByteArray?): BleAdvertisement? {
        if (raw == null) return null
        var manufacturer: ByteArray? = null
        var localName: String? = null
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + 1 >= raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val dataStart = i + 2
            val dataEnd = i + 1 + len
            if (dataEnd > raw.size) break
            when (type) {
                0xFF -> if (dataEnd - dataStart >= 2) {
                    val company = (raw[dataStart].toInt() and 0xFF) or ((raw[dataStart + 1].toInt() and 0xFF) shl 8)
                    if (company == MANUFACTURER_ID) manufacturer = raw.copyOfRange(dataStart + 2, dataEnd)
                }
                0x08, 0x09 -> localName = decodeUtf8Lenient(raw.copyOfRange(dataStart, dataEnd))
            }
            i = dataEnd
        }
        return parseManufacturerData(manufacturer) ?: parseIosLocalName(localName)
    }

    /** `true` when [lanPeerId] belongs to the BLE peer [id6] (LAN transport wins in the merged list). */
    fun matchesId6(lanPeerId: String, id6: String): Boolean =
        id6.length == ID6_LEN && id6Of(lanPeerId).equals(id6, ignoreCase = true)

    // ---------------------------------------------------------------------------------------------
    // Framing (spec §2.2)
    // ---------------------------------------------------------------------------------------------

    /** Android central chunk: `clamp(negotiatedMtu − 4, 19, 240)`. */
    fun chunkForMtu(mtu: Int): Int = (mtu - 4).coerceIn(MIN_CHUNK, MAX_CHUNK)

    /** Splits [message] into frames `[hdr][≤ chunk bytes]`, `hdr` bit7 = LAST, bits0–6 = seq (mod 128). */
    fun split(message: ByteArray, chunk: Int): List<ByteArray> {
        require(message.size <= MAX_MESSAGE_BYTES) { "BLE message exceeds $MAX_MESSAGE_BYTES bytes: ${message.size}" }
        val c = chunk.coerceIn(1, MAX_CHUNK)
        val frames = ArrayList<ByteArray>((message.size + c - 1) / c + 1)
        if (message.isEmpty()) {
            frames.add(byteArrayOf(HDR_LAST.toByte()))
            return frames
        }
        var off = 0
        var seq = 0
        while (off < message.size) {
            val n = minOf(c, message.size - off)
            val last = off + n >= message.size
            val frame = ByteArray(n + 1)
            frame[0] = ((seq and SEQ_MASK) or (if (last) HDR_LAST else 0)).toByte()
            System.arraycopy(message, off, frame, 1, n)
            frames.add(frame)
            off += n
            seq = (seq + 1) and SEQ_MASK
        }
        return frames
    }

    /**
     * Reassembler (spec §2.2 [v2]): hard cap [maxBytes]; a frame with `seq == 0` always starts a new message;
     * after a sequence gap everything is dropped until the next `seq == 0`. Not thread-safe.
     */
    class Reassembler(private val maxBytes: Int = MAX_MESSAGE_BYTES) {
        private val buf = ByteArrayOutputStream()
        private var expectedSeq = -1 // -1 = idle / dropping until seq 0

        fun reset() {
            buf.reset()
            expectedSeq = -1
        }

        /** Feeds one frame; returns the complete message when a LAST frame closes it, else `null`. */
        fun feed(frame: ByteArray?): ByteArray? {
            if (frame == null || frame.isEmpty()) return null
            val hdr = frame[0].toInt() and 0xFF
            val seq = hdr and SEQ_MASK
            val last = hdr and HDR_LAST != 0
            if (seq == 0) {
                buf.reset()
                expectedSeq = 0
            }
            if (expectedSeq < 0 || seq != expectedSeq) {
                reset()
                return null
            }
            if (buf.size() + frame.size - 1 > maxBytes) {
                reset()
                return null
            }
            buf.write(frame, 1, frame.size - 1)
            if (last) {
                val out = buf.toByteArray()
                reset()
                return out
            }
            expectedSeq = (seq + 1) and SEQ_MASK
            return null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Messages (spec §2.2 INFO, §2.3)
    // ---------------------------------------------------------------------------------------------

    fun encodeMessage(msg: HotspotLinkMessage): ByteArray = MiniJson.stringify(toJson(msg)).toByteArray(Charsets.UTF_8)

    fun toJson(msg: HotspotLinkMessage): JsonValue.Obj = when (msg) {
        is HotspotLinkMessage.Hello -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "v" to JsonValue.Num(msg.v),
            "id" to JsonValue.Str(msg.id),
            "name" to JsonValue.Str(msg.name),
            "os" to JsonValue.Str(msg.os),
            "ver" to JsonValue.Str(msg.ver),
            "files" to JsonValue.Num(msg.files),
            "bytes" to JsonValue.Num(msg.bytes),
            "tok" to JsonValue.Str(msg.tok),
            "lan" to jsonStrList(msg.lan),
        )
        is HotspotLinkMessage.Ack -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "id" to JsonValue.Str(msg.id),
            "name" to JsonValue.Str(msg.name),
            "os" to JsonValue.Str(msg.os),
            "caps" to jsonStrList(msg.caps),
            "port" to JsonValue.Num(msg.port),
            "lan" to jsonStrList(msg.lan),
        )
        is HotspotLinkMessage.Decline -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "reason" to JsonValue.Str(msg.reason),
        )
        is HotspotLinkMessage.Link -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "mode" to JsonValue.Str(msg.mode.wire),
        )
        is HotspotLinkMessage.Hotspot -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "ssid" to JsonValue.Str(msg.ssid),
            "pass" to JsonValue.Str(msg.pass),
            "sec" to JsonValue.Str(msg.sec),
            "ip" to JsonValue.Str(msg.ip),
            "port" to JsonValue.Num(msg.port),
            "tok" to JsonValue.Str(msg.tok),
        )
        is HotspotLinkMessage.Joined -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "ip" to JsonValue.Str(msg.ip),
        )
        is HotspotLinkMessage.Ready -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "ip" to JsonValue.Str(msg.ip),
            "port" to JsonValue.Num(msg.port),
        )
        is HotspotLinkMessage.Done -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "ok" to JsonValue.Bool(msg.ok),
        )
        is HotspotLinkMessage.Error -> MiniJson.obj(
            "t" to JsonValue.Str(msg.type),
            "code" to JsonValue.Str(msg.code),
        )
    }

    /** Decodes a reassembled UTF-8 JSON message; unknown `t` / malformed input ⇒ `null`. */
    fun decodeMessage(bytes: ByteArray): HotspotLinkMessage? {
        val obj = try {
            MiniJson.parse(String(bytes, Charsets.UTF_8)) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        } ?: return null
        return fromJson(obj)
    }

    fun fromJson(o: JsonValue.Obj): HotspotLinkMessage? = when (o.str("t")) {
        "hello" -> {
            val tok = o.str("tok").orEmpty()
            HotspotLinkMessage.Hello(
                id = o.str("id").orEmpty(),
                name = o.str("name").orEmpty(),
                os = o.str("os").orEmpty(),
                ver = o.str("ver").orEmpty(),
                files = o.int("files", 0),
                bytes = o.long("bytes", 0L),
                tok = tok,
                lan = o.strList("lan"),
                v = o.int("v", 1),
            ).takeIf { it.id.isNotEmpty() && isValidToken(tok) }
        }
        "ack" -> HotspotLinkMessage.Ack(
            id = o.str("id").orEmpty(),
            name = o.str("name").orEmpty(),
            os = o.str("os").orEmpty(),
            caps = o.strList("caps"),
            port = o.int("port", 0),
            lan = o.strList("lan"),
        )
        "decline" -> HotspotLinkMessage.Decline(o.str("reason") ?: HotspotLinkMessage.DECLINE_USER)
        "link" -> LinkMode.fromWire(o.str("mode"))?.let { HotspotLinkMessage.Link(it) }
        "hotspot" -> {
            val ssid = o.str("ssid").orEmpty()
            val port = o.int("port", 0)
            if (ssid.isEmpty() || port !in 1..65535) {
                null
            } else {
                HotspotLinkMessage.Hotspot(
                    ssid = ssid,
                    pass = o.str("pass").orEmpty(),
                    sec = o.str("sec") ?: "wpa2",
                    ip = o.str("ip").orEmpty(),
                    port = port,
                    tok = o.str("tok").orEmpty(),
                )
            }
        }
        "joined" -> HotspotLinkMessage.Joined(o.str("ip").orEmpty())
        "ready" -> HotspotLinkMessage.Ready(o.str("ip").orEmpty(), o.int("port", 0))
        "done" -> HotspotLinkMessage.Done(o.bool("ok", false))
        "error" -> HotspotLinkMessage.Error(o.str("code") ?: HotspotLinkMessage.ERR_CANCELLED)
        else -> null
    }

    fun encodePeerCard(card: BlePeerCard): ByteArray = MiniJson.stringify(
        MiniJson.obj(
            "v" to JsonValue.Num(card.v),
            "id" to JsonValue.Str(card.id),
            "name" to JsonValue.Str(card.name),
            "os" to JsonValue.Str(card.os),
            "ver" to JsonValue.Str(card.ver),
            "caps" to jsonStrList(card.caps),
            "port" to JsonValue.Num(card.port),
            "lan" to jsonStrList(card.lan),
        ),
    ).toByteArray(Charsets.UTF_8)

    fun decodePeerCard(bytes: ByteArray): BlePeerCard? {
        val o = try {
            MiniJson.parse(String(bytes, Charsets.UTF_8)) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        } ?: return null
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return BlePeerCard(
            id = id,
            name = o.str("name").orEmpty(),
            os = o.str("os").orEmpty(),
            ver = o.str("ver").orEmpty(),
            caps = o.strList("caps"),
            port = o.int("port", 0),
            lan = o.strList("lan"),
            v = o.int("v", 1),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Tokens (spec §2.3 [v2], §2.4)
    // ---------------------------------------------------------------------------------------------

    private val secureRandom: SecureRandom by lazy { SecureRandom() }

    /** 128-bit CSPRNG token as 32 lowercase hex chars. */
    fun generateToken(random: SecureRandom = secureRandom): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(TOKEN_HEX_LEN)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun isValidToken(tok: String?): Boolean =
        tok != null && tok.length == TOKEN_HEX_LEN && tok.all { it in '0'..'9' || it in 'a'..'f' }

    /** Constant-time comparison (time depends only on the lengths, never on where the first mismatch is). */
    fun constantTimeEquals(expected: String, actual: String): Boolean {
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = actual.toByteArray(Charsets.UTF_8)
        var diff = a.size xor b.size
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = if (i < a.size) a[i].toInt() else 0
            val y = if (i < b.size) b[i].toInt() else 0
            diff = diff or (x xor y)
        }
        return diff == 0
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private const val HEX = "0123456789abcdef"

    private fun cleanName(name: String): String = PeerPathPolicy.sanitizeDisplayName(name, 64)

    /** UTF-8 encode and cut at a code-point boundary so the result is ≤ [maxBytes]. */
    fun truncateUtf8(s: String, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream(maxBytes)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val enc = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + enc.size > maxBytes) break
            out.write(enc)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }

    private fun decodeUtf8Lenient(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).trimEnd('\u0000', '\uFFFD')
}
