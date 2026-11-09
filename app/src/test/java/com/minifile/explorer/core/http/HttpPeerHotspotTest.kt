package com.minifile.explorer.core.http

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.peer.HotspotLinkCodec
import com.minifile.explorer.core.peer.PeerHttpClient
import com.minifile.explorer.core.peer.PeerHttpResponse
import com.minifile.explorer.core.peer.PeerInfo
import com.minifile.explorer.core.peer.PeerSource
import com.minifile.explorer.core.peer.PeerTransferEngine
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

/** Spec §2.4 hotspot additions: `/api/peer/info` fields, `/api/peer/announce`, `begin` token gate, confinement. */
class HttpPeerHotspotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private var server: HttpFileServerEngine? = null
    private var nowMs = 1_000_000L
    private lateinit var gate: HotspotSessionGate
    private val client = PeerHttpClient(connectTimeoutMs = 3_000, soTimeoutMs = 5_000)

    private val tok = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("root")
        gate = HotspotSessionGate(nowMs = { nowMs })
    }

    @After
    fun tearDown() {
        client.close()
        server?.stop()
        server = null
    }

    private fun start(): HttpFileServerEngine {
        val srv = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(rootDir.canonicalPath),
                deviceId = "ab12cd34-self",
                deviceName = "Pixel 8",
            ),
            fs = LocalFileSystem(),
            hotspotGate = gate,
        )
        srv.start()
        server = srv
        return srv
    }

    private fun base(srv: HttpFileServerEngine) = "http://127.0.0.1:${srv.boundPort}"

    private fun json(resp: PeerHttpResponse): JsonValue.Obj = MiniJson.parse(resp.bodyText) as JsonValue.Obj

    private fun announceUrl(srv: HttpFileServerEngine, token: String) =
        "${base(srv)}/api/peer/announce?tok=$token&port=8123&id=ios-device-1&name=iPhone%2015&os=ios"

    @Test
    fun info_reportsOsAndHotspotFlag() {
        val srv = start()
        val r1 = json(client.get("${base(srv)}/api/peer/info"))
        assertEquals("android", r1.str("os"))
        assertFalse(r1.bool("hotspot", true))
        assertEquals("Files", r1.str("app"))
        assertEquals(1, r1.int("proto"))

        gate.hotspotBindIp = InetAddress.getByName("127.0.0.1")
        val r2 = json(client.get("${base(srv)}/api/peer/info"))
        assertTrue(r2.bool("hotspot", false))
    }

    @Test
    fun announce_unarmed_isNeverOpen() {
        val srv = start()
        val resp = client.postEmpty(announceUrl(srv, tok))
        assertEquals(403, resp.status)
        assertEquals("token", json(resp).str("code"))
    }

    @Test
    fun announce_wrongToken_rejected_withoutConsumingArmedToken() {
        val srv = start()
        gate.armAnnounceToken(tok)
        val bad = client.postEmpty(announceUrl(srv, "ffffffffffffffffffffffffffffffff"))
        assertEquals(403, bad.status)
        assertEquals("token", json(bad).str("code"))
        val good = client.postEmpty(announceUrl(srv, tok))
        assertEquals(200, good.status)
    }

    @Test
    fun announce_success_emitsEvent_andIsSingleUse() = runBlocking {
        val srv = start()
        gate.armAnnounceToken(tok)
        val ev = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { srv.events.first { it is HttpEvent.PeerAnnounce } as HttpEvent.PeerAnnounce }
        }
        val resp = client.postEmpty(announceUrl(srv, tok))
        assertEquals(200, resp.status)
        val body = json(resp)
        assertTrue(body.bool("ok", false))
        assertEquals("127.0.0.1", body.str("ip"))

        val announce = ev.await()
        assertEquals("127.0.0.1", announce.clientIp)
        assertEquals(8123, announce.port)
        assertEquals("ios-device-1", announce.id)
        assertEquals("iPhone 15", announce.name)
        assertEquals("ios", announce.os)

        val again = client.postEmpty(announceUrl(srv, tok))
        assertEquals(403, again.status)
        assertFalse(gate.isAnnounceArmed)
    }

    @Test
    fun announce_ttlExpiry_rejects() {
        val srv = start()
        gate.armAnnounceToken(tok)
        nowMs += HotspotSessionGate.TOKEN_TTL_MS + 1
        assertEquals(403, client.postEmpty(announceUrl(srv, tok)).status)
    }

    @Test
    fun announce_invalidPort_400_andTokenSurvives() {
        val srv = start()
        gate.armAnnounceToken(tok)
        val resp = client.postEmpty("${base(srv)}/api/peer/announce?tok=$tok&port=0&id=x")
        assertEquals(400, resp.status)
        assertTrue(gate.isAnnounceArmed)
    }

    @Test
    fun announce_whileHosting_mustArriveOnHotspotInterface() {
        val srv = start()
        gate.armAnnounceToken(tok)
        // Hosting on a different address than the socket's local address (127.0.0.1) ⇒ spoof over "wlan0".
        gate.hotspotBindIp = InetAddress.getByName("127.0.0.2")
        val spoof = client.postEmpty(announceUrl(srv, tok))
        assertEquals(403, spoof.status)
        assertTrue("token must not be burned by a spoofed announce", gate.isAnnounceArmed)

        gate.hotspotBindIp = InetAddress.getByName("127.0.0.1")
        assertEquals(200, client.postEmpty(announceUrl(srv, tok)).status)
    }

    /** Client whose sockets originate from 127.0.0.2 so the server sees a joiner distinct from the AP (127.0.0.1). */
    private fun joinerClient() = PeerHttpClient(
        connectTimeoutMs = 3_000,
        soTimeoutMs = 5_000,
        socketBinder = { s, _ -> s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0)) },
    )

    private fun openAnnounceUrl(srv: HttpFileServerEngine, id: String = "ios-cam-1") =
        "${base(srv)}/api/peer/announce?port=8123&id=$id&name=iPhone%2016&os=ios"

    @Test
    fun announce_hotspotDirectOpen_acceptsTokenlessJoiner_andEmitsOpenEvent() = runBlocking {
        val srv = start()
        gate.hotspotBindIp = InetAddress.getByName("127.0.0.1")
        gate.hotspotPrefixLength = 8
        gate.setOpenAnnounce(true)
        val ev = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { srv.events.first { it is HttpEvent.PeerAnnounce } as HttpEvent.PeerAnnounce }
        }
        joinerClient().use { c ->
            val resp = c.postEmpty(openAnnounceUrl(srv))
            assertEquals(200, resp.status)
            assertEquals("127.0.0.2", json(resp).str("ip"))
            // Empty tok is treated the same as absent.
            assertEquals(200, c.postEmpty(openAnnounceUrl(srv) + "&tok=").status)
        }
        val announce = ev.await()
        assertTrue(announce.open)
        assertEquals("127.0.0.2", announce.clientIp)
        assertEquals(8123, announce.port)
        assertEquals("ios-cam-1", announce.id)
        assertEquals("iPhone 16", announce.name)
        assertEquals("ios", announce.os)
    }

    @Test
    fun announce_hotspotDirectOpen_rejectsOffHotspotSubnetAndRateLimits() {
        val srv = start()
        gate.setOpenAnnounce(true)
        joinerClient().use { c ->
            // Not hosting yet (no hotspot IP) ⇒ refused.
            assertEquals(403, c.postEmpty(openAnnounceUrl(srv)).status)
            // Arrives on a non-hotspot address (STA spoof).
            gate.hotspotBindIp = InetAddress.getByName("127.0.0.3")
            assertEquals(403, c.postEmpty(openAnnounceUrl(srv)).status)
            // Right interface, but client outside the hotspot subnet.
            gate.hotspotBindIp = InetAddress.getByName("127.0.0.1")
            gate.hotspotPrefixLength = 31
            val outside = c.postEmpty(openAnnounceUrl(srv))
            assertEquals(403, outside.status)
            assertEquals("token", json(outside).str("code"))

            gate.hotspotPrefixLength = 8
            repeat(HotspotSessionGate.OPEN_ANNOUNCE_MAX_PER_WINDOW) {
                assertEquals(200, c.postEmpty(openAnnounceUrl(srv)).status)
            }
            val limited = c.postEmpty(openAnnounceUrl(srv))
            assertEquals(429, limited.status)
            assertEquals("rate", json(limited).str("code"))
        }
    }

    @Test
    fun announce_armedTokenStillRequired_evenWhenOpenAnnounceEnabled() {
        val srv = start()
        gate.hotspotBindIp = InetAddress.getByName("127.0.0.1")
        gate.hotspotPrefixLength = 8
        gate.setOpenAnnounce(true)
        gate.armAnnounceToken(tok)
        joinerClient().use { c ->
            assertEquals(403, c.postEmpty(openAnnounceUrl(srv)).status)
            assertTrue(gate.isAnnounceArmed)
            assertEquals(200, c.postEmpty(openAnnounceUrl(srv) + "&tok=$tok").status)
            assertFalse(gate.isAnnounceArmed)
        }
    }

    @Test
    fun ephemeralShare_isConfinedToHotspotInterface() {
        val srv = start()
        gate.hotspotBindIp = InetAddress.getByName("127.0.0.2")
        gate.confineToHotspot = true
        val blocked = client.get("${base(srv)}/api/peer/info")
        assertEquals(403, blocked.status)
        assertEquals(403, client.get("${base(srv)}/").status)

        gate.hotspotBindIp = InetAddress.getByName("127.0.0.1")
        assertEquals(200, client.get("${base(srv)}/api/peer/info").status)

        gate.confineToHotspot = false
        gate.hotspotBindIp = InetAddress.getByName("127.0.0.2")
        assertEquals(200, client.get("${base(srv)}/api/peer/info").status)
    }

    @Test
    fun begin_tokenGate_whenSessionArmed_andClearedOnEnd() {
        val srv = start()
        gate.armSessionToken(tok)
        val noTok = client.postEmpty("${base(srv)}/api/peer/begin?files=1&bytes=3&device=Phone")
        assertEquals(403, noTok.status)
        assertEquals("token", json(noTok).str("code"))
        val wrong = client.postEmpty("${base(srv)}/api/peer/begin?files=1&bytes=3&device=Phone&tok=00000000000000000000000000000000")
        assertEquals(403, wrong.status)

        val ok = client.postEmpty("${base(srv)}/api/peer/begin?files=1&bytes=3&device=Phone&tok=$tok")
        assertEquals(200, ok.status)
        val session = json(ok).str("session")!!
        val end = client.postEmpty("${base(srv)}/api/peer/end?session=$session&ok=1")
        assertEquals(200, end.status)
        assertFalse(gate.isSessionArmed)

        // LAN behaviour restored: no token needed any more.
        assertEquals(200, client.postEmpty("${base(srv)}/api/peer/begin?files=0&bytes=0&device=Phone").status)
    }

    @Test
    fun begin_sessionTokenTtl_neverLeavesLanLocked() {
        val srv = start()
        gate.armSessionToken(tok)
        nowMs += HotspotSessionGate.TOKEN_TTL_MS
        assertEquals(200, client.postEmpty("${base(srv)}/api/peer/begin?files=0&bytes=0&device=Phone").status)
    }

    @Test
    fun begin_unarmed_isUnchanged() {
        val srv = start()
        assertEquals(200, client.postEmpty("${base(srv)}/api/peer/begin?files=0&bytes=0&device=Phone").status)
        // A stray tok on plain LAN is ignored.
        assertEquals(200, client.postEmpty("${base(srv)}/api/peer/begin?files=0&bytes=0&device=Phone&tok=$tok").status)
    }

    @Test
    fun gate_reset_clearsEverything() {
        gate.armSessionToken(tok)
        gate.armAnnounceToken(tok)
        gate.hotspotBindIp = InetAddress.getByName("127.0.0.1")
        gate.confineToHotspot = true
        gate.reset()
        assertFalse(gate.isSessionArmed)
        assertFalse(gate.isAnnounceArmed)
        assertFalse(gate.isHosting)
        assertFalse(gate.confineToHotspot)
    }

    @Test
    fun senderErrorMapping_iosCodes_D3() {
        val peer = PeerInfo("id", "iPhone", "172.20.10.1", 8080, "1.0.18", false, PeerSource.BLE, 0L)
        fun map(code: String) = PeerTransferEngine.mapHttpError(
            peer,
            PeerHttpResponse(403, "Forbidden", emptyMap(), """{"ok":false,"code":"$code"}""".toByteArray()),
        ).userMessage
        assertEquals("Declined by iPhone", map("declined"))
        assertEquals("iPhone has peer receiving off", map("disabled"))
        assertEquals("Session expired — reconnect to iPhone", map("token"))
        assertEquals(150_000, PeerTransferEngine.BEGIN_TIMEOUT_MS)
    }

    @Test
    fun tokenHelpers_constantTimeAndFormat() {
        assertTrue(HotspotLinkCodec.constantTimeEquals(tok, tok))
        assertFalse(HotspotLinkCodec.constantTimeEquals(tok, tok.dropLast(1)))
        assertFalse(HotspotLinkCodec.constantTimeEquals(tok, tok.dropLast(1) + "e"))
    }
}
