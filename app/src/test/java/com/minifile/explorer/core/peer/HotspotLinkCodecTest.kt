package com.minifile.explorer.core.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class HotspotLinkCodecTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    // ---------------------------------------------------------------- §6 shared vectors

    @Test
    fun androidScanResponse_matchesSharedVector() {
        val flags = HotspotLinkCodec.FLAG_ANDROID or HotspotLinkCodec.FLAG_CAN_HOST or HotspotLinkCodec.FLAG_RECEIVING
        val payload = HotspotLinkCodec.encodeAndroidManufacturerData("ab12cd34-5678", "Pixel 8", flags)
        assertEquals("01 07 61 62 31 32 63 64 50 69 78 65 6C 20 38", hex(payload))
        assertEquals(15, payload.size)

        val parsed = HotspotLinkCodec.parseManufacturerData(payload)!!
        assertEquals("android", parsed.platform)
        assertEquals("ab12cd", parsed.id6)
        assertEquals("Pixel 8", parsed.name)
        assertEquals(0x07, parsed.flags)
        assertTrue(parsed.canHost)
        assertTrue(parsed.receiving)
        assertFalse(parsed.busy)
    }

    @Test
    fun iosLocalName_matchesSharedVector() {
        assertEquals("MF:ab12cd:Pixel 8", HotspotLinkCodec.encodeIosLocalName("ab12cd99", "Pixel 8"))
        val p = HotspotLinkCodec.parseIosLocalName("MF:ab12cd:Pixel 8")!!
        assertEquals("ios", p.platform)
        assertEquals("ab12cd", p.id6)
        assertEquals("Pixel 8", p.name)
        assertNull(HotspotLinkCodec.parseIosLocalName("Pixel 8"))
        assertNull(HotspotLinkCodec.parseIosLocalName("MF:ab1:x"))
    }

    @Test
    fun split131BytesAtMtu23_gives7Frames_last0x86() {
        val msg = hotspotMessageOfLength(131)
        assertEquals(131, msg.size)
        val chunk = HotspotLinkCodec.chunkForMtu(23)
        assertEquals(19, chunk)
        val frames = HotspotLinkCodec.split(msg, chunk)
        assertEquals(7, frames.size)
        assertEquals(0x86, frames.last()[0].toInt() and 0xFF)
        for (i in 0 until 6) {
            assertEquals(i, frames[i][0].toInt() and 0xFF)
            assertEquals(20, frames[i].size) // 1 B hdr + 19 B = ATT value at MTU 23
        }
        val r = HotspotLinkCodec.Reassembler()
        var out: ByteArray? = null
        for (f in frames) out = r.feed(f) ?: out
        assertArrayEquals(msg, out)
        val decoded = HotspotLinkCodec.decodeMessage(out!!) as HotspotLinkMessage.Hotspot
        assertEquals("10.0.0.1", decoded.ip)
    }

    @Test
    fun split131BytesAtMtu185_givesSingleFrame0x80() {
        val msg = hotspotMessageOfLength(131)
        val chunk = HotspotLinkCodec.chunkForMtu(185)
        assertEquals(181, chunk)
        val frames = HotspotLinkCodec.split(msg, chunk)
        assertEquals(1, frames.size)
        assertEquals(0x80, frames[0][0].toInt() and 0xFF)
        assertEquals(132, frames[0].size)
    }

    @Test
    fun reassembler_gap_dropsMessage_andResumesAtNextSeq0() {
        val msg = ByteArray(19 * 4) { it.toByte() }
        val frames = HotspotLinkCodec.split(msg, 19)
        val r = HotspotLinkCodec.Reassembler()
        assertNull(r.feed(frames[0]))
        assertNull(r.feed(frames[1]))
        assertNull(r.feed(frames[3])) // seq 3 after 1 ⇒ gap ⇒ dropped (and it was LAST)
        assertNull(r.feed(frames[2])) // still dropping until seq 0
        // Next message starting at seq 0 is accepted.
        var out: ByteArray? = null
        for (f in frames) out = r.feed(f) ?: out
        assertArrayEquals(msg, out)
    }

    @Test
    fun reassembler_seq0_alwaysRestarts() {
        val a = HotspotLinkCodec.split(ByteArray(40) { 1 }, 19)
        val b = HotspotLinkCodec.split(ByteArray(10) { 2 }, 19)
        val r = HotspotLinkCodec.Reassembler()
        assertNull(r.feed(a[0]))
        val out = r.feed(b[0])
        assertArrayEquals(ByteArray(10) { 2 }, out)
    }

    @Test
    fun reassembler_capsAt2048Bytes() {
        val r = HotspotLinkCodec.Reassembler()
        val chunk = 240
        // 9 non-last frames of 240 B = 2160 B > 2048 ⇒ dropped
        var result: ByteArray? = null
        for (seq in 0 until 9) {
            val f = ByteArray(chunk + 1)
            f[0] = (seq or (if (seq == 8) 0x80 else 0)).toByte()
            result = r.feed(f) ?: result
        }
        assertNull(result)
        // Exactly 2048 is fine.
        val ok = HotspotLinkCodec.split(ByteArray(2048) { 7 }, 240)
        var out: ByteArray? = null
        for (f in ok) out = r.feed(f) ?: out
        assertEquals(2048, out!!.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun split_rejectsOversizedMessage() {
        HotspotLinkCodec.split(ByteArray(2049), 19)
    }

    @Test
    fun chunkForMtu_clamps() {
        assertEquals(19, HotspotLinkCodec.chunkForMtu(10))
        assertEquals(19, HotspotLinkCodec.chunkForMtu(23))
        assertEquals(240, HotspotLinkCodec.chunkForMtu(517))
    }

    // ---------------------------------------------------------------- advertisement

    @Test
    fun androidName_truncatedAtCharBoundary_to19Bytes() {
        val payload = HotspotLinkCodec.encodeAndroidManufacturerData("zz99yy", "小米手机的名字很长很长", 0x01)
        assertTrue(payload.size <= 27)
        val nameBytes = payload.size - 8
        assertTrue(nameBytes <= 19)
        assertEquals(0, nameBytes % 3) // CJK = 3 B each, never split mid-char
        val parsed = HotspotLinkCodec.parseManufacturerData(payload)!!
        assertEquals("小米手机的名", parsed.name)
    }

    @Test
    fun iosLocalName_isAtMost22Bytes() {
        val s = HotspotLinkCodec.encodeIosLocalName("abcdef00", "Chii’s very long iPhone name")
        assertTrue(s.toByteArray(Charsets.UTF_8).size <= 22)
        assertTrue(s.startsWith("MF:abcdef:"))
    }

    @Test
    fun parseScanRecord_rawAdStructures() {
        val manu = HotspotLinkCodec.encodeAndroidManufacturerData("ab12cd", "Pixel 8", 0x07)
        val raw = byteArrayOf(0x02, 0x01, 0x06) + // flags
            byteArrayOf((manu.size + 3).toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + manu
        val adv = HotspotLinkCodec.parseScanRecord(raw)!!
        assertEquals("ab12cd", adv.id6)
        assertEquals("Pixel 8", adv.name)

        val name = "MF:ab12cd:iPhone".toByteArray()
        val iosRaw = byteArrayOf((name.size + 1).toByte(), 0x09) + name
        assertEquals("ios", HotspotLinkCodec.parseScanRecord(iosRaw)!!.platform)

        // Foreign company id ⇒ ignored.
        val foreign = byteArrayOf(0x05, 0xFF.toByte(), 0x4C, 0x00, 0x01, 0x02)
        assertNull(HotspotLinkCodec.parseScanRecord(foreign))
        // Truncated structure never throws.
        assertNull(HotspotLinkCodec.parseScanRecord(byteArrayOf(0x1F, 0xFF.toByte(), 0x01)))
    }

    @Test
    fun id6_andLanDedupe() {
        assertEquals("ab12cd", HotspotLinkCodec.id6Of("ab12cd34-5678"))
        assertEquals("ab0000", HotspotLinkCodec.id6Of("ab"))
        assertTrue(HotspotLinkCodec.matchesId6("ab12cd34-5678", "ab12cd"))
        assertFalse(HotspotLinkCodec.matchesId6("ab12ce34", "ab12cd"))

        val lan = PeerInfo("ab12cd34-5678", "Pixel", "192.168.1.5", 8080, "1.0.18", false, PeerSource.BEACON, 0L)
        val bleDup = PeerInfo("ab12cd", "Pixel", "", 0, "", false, PeerSource.BLE, 0L)
        val bleOther = PeerInfo("ff00ee", "iPhone", "", 0, "", false, PeerSource.BLE, 0L, platform = "ios")
        val merged = PeerMerge.merge(listOf(lan), listOf(bleDup, bleOther))
        assertEquals(listOf(lan, bleOther), merged)
        assertTrue(bleOther.needsLink)
        assertEquals("Bluetooth", bleOther.displayEndpoint)
    }

    // ---------------------------------------------------------------- messages

    @Test
    fun messages_roundTrip_withExactWireKeys() {
        val hello = HotspotLinkMessage.Hello(
            id = "ab12cd34", name = "Pixel 8", os = "android", ver = "1.0.18",
            files = 3, bytes = 1234567L, tok = "0123456789abcdef0123456789abcdef", lan = listOf("192.168.1.50"),
        )
        val json = String(HotspotLinkCodec.encodeMessage(hello))
        assertEquals(
            """{"t":"hello","v":1,"id":"ab12cd34","name":"Pixel 8","os":"android","ver":"1.0.18","files":3,"bytes":1234567,"tok":"0123456789abcdef0123456789abcdef","lan":["192.168.1.50"]}""",
            json,
        )
        val all = listOf(
            hello,
            HotspotLinkMessage.Ack("id", "n", "ios", listOf("lohs", "join", "p2p"), 8080, listOf("10.0.0.2")),
            HotspotLinkMessage.Decline("busy"),
            HotspotLinkMessage.Link(LinkMode.LOHS_R),
            HotspotLinkMessage.Link(LinkMode.LOHS_S),
            HotspotLinkMessage.Link(LinkMode.LAN),
            HotspotLinkMessage.Hotspot("AndroidShare_7781", "k2Jd9xQ1", "wpa2", "192.168.197.1", 8080, "0123456789abcdef0123456789abcdef"),
            HotspotLinkMessage.Joined("192.168.197.23"),
            HotspotLinkMessage.Ready("192.168.197.40", 8080),
            HotspotLinkMessage.Done(true),
            HotspotLinkMessage.Error("lohs_failed"),
        )
        for (m in all) {
            assertEquals(m, HotspotLinkCodec.decodeMessage(HotspotLinkCodec.encodeMessage(m)))
        }
        assertEquals(
            """{"t":"link","mode":"lohs-r"}""",
            String(HotspotLinkCodec.encodeMessage(HotspotLinkMessage.Link(LinkMode.LOHS_R))),
        )
        assertEquals(
            """{"t":"hotspot","ssid":"S","pass":"P","sec":"wpa3t","ip":"192.168.5.1","port":8081,"tok":"t"}""",
            String(HotspotLinkCodec.encodeMessage(HotspotLinkMessage.Hotspot("S", "P", "wpa3t", "192.168.5.1", 8081, "t"))),
        )
    }

    @Test
    fun decode_rejectsMalformedAndUnknown() {
        assertNull(HotspotLinkCodec.decodeMessage("not json".toByteArray()))
        assertNull(HotspotLinkCodec.decodeMessage("""{"t":"nope"}""".toByteArray()))
        assertNull(HotspotLinkCodec.decodeMessage("""{"t":"link","mode":"weird"}""".toByteArray()))
        // hello requires a valid 32-hex token
        assertNull(HotspotLinkCodec.decodeMessage("""{"t":"hello","id":"x","tok":"short"}""".toByteArray()))
        assertNull(HotspotLinkCodec.decodeMessage("""{"t":"hotspot","ssid":"","port":8080}""".toByteArray()))
    }

    @Test
    fun peerCard_roundTrip() {
        val card = BlePeerCard("ab12cd34", "Pixel 8", "android", "1.0.18", listOf("lohs", "join"), 8080, listOf("192.168.1.50"))
        val bytes = HotspotLinkCodec.encodePeerCard(card)
        assertTrue(bytes.size <= 512)
        assertEquals(card, HotspotLinkCodec.decodePeerCard(bytes))
        assertTrue(String(bytes).startsWith("""{"v":1,"id":"ab12cd34""""))
    }

    // ---------------------------------------------------------------- decision + tokens

    @Test
    fun hostDecision_table() {
        assertEquals(LinkMode.LAN, HostDecision.decide("android", "ios", lanOverlap = true))
        assertEquals(LinkMode.LOHS_R, HostDecision.decide("android", "android", false))
        assertEquals(LinkMode.LOHS_R, HostDecision.decide("ios", "android", false))
        assertEquals(LinkMode.LOHS_S, HostDecision.decide("android", "ios", false))
        assertNull(HostDecision.decide("ios", "ios", false))
        assertTrue(HostDecision.sharesSlash24(listOf("192.168.1.10"), listOf("10.0.0.1", "192.168.1.77")))
        assertFalse(HostDecision.sharesSlash24(listOf("192.168.1.10"), listOf("192.168.2.10")))
        assertTrue(HostDecision.inSubnet("192.168.197.40", "192.168.197.1", 24))
        assertFalse(HostDecision.inSubnet("192.168.198.40", "192.168.197.1", 24))
        assertTrue(HostDecision.inSubnet("10.20.5.9", "10.20.0.1", 16))
        assertFalse(HostDecision.inSubnet("garbage", "10.20.0.1", 16))
    }

    @Test
    fun tokens_are128BitHex_andCompareConstantTime() {
        val seen = HashSet<String>()
        repeat(200) {
            val t = HotspotLinkCodec.generateToken(SecureRandom())
            assertEquals(32, t.length)
            assertTrue(HotspotLinkCodec.isValidToken(t))
            assertTrue(seen.add(t))
        }
        assertFalse(HotspotLinkCodec.isValidToken("0123456789ABCDEF0123456789abcdef"))
        assertFalse(HotspotLinkCodec.isValidToken(null))
        val a = "0123456789abcdef0123456789abcdef"
        assertTrue(HotspotLinkCodec.constantTimeEquals(a, String(a.toCharArray())))
        assertFalse(HotspotLinkCodec.constantTimeEquals(a, "1123456789abcdef0123456789abcdef"))
        assertFalse(HotspotLinkCodec.constantTimeEquals(a, a + "0"))
        assertFalse(HotspotLinkCodec.constantTimeEquals(a, ""))
    }

    @Test
    fun uuids_matchSpec() {
        assertEquals("4d465031-0001-4d46-8000-6d696e696669", HotspotLinkUuids.SERVICE_UUID.toString())
        assertEquals("4d465031-0002-4d46-8000-6d696e696669", HotspotLinkUuids.INFO_UUID.toString())
        assertEquals("4d465031-0003-4d46-8000-6d696e696669", HotspotLinkUuids.CTRL_UUID.toString())
        assertEquals("4d465031-0004-4d46-8000-6d696e696669", HotspotLinkUuids.EVT_UUID.toString())
        assertNotNull(HotspotLinkUuids.CCCD_UUID)
    }

    /** Builds a `hotspot` message whose UTF-8 JSON is exactly [len] bytes (sizes the SSID to fit). */
    private fun hotspotMessageOfLength(len: Int): ByteArray {
        val base = HotspotLinkMessage.Hotspot("", "k2Jd9xQ1", "wpa2", "10.0.0.1", 8080, "0123456789abcdef0123456789abcdef")
        val baseLen = HotspotLinkCodec.encodeMessage(base).size
        val need = len - baseLen
        check(need >= 1) { "base message already $baseLen bytes" }
        val ssid = ("AndroidShare_7781" + "x".repeat(64)).take(need)
        val bytes = HotspotLinkCodec.encodeMessage(base.copy(ssid = ssid))
        check(bytes.size == len) { "built ${bytes.size} != $len" }
        return bytes
    }
}
