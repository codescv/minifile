package com.minifile.explorer.core.http

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.peer.PeerHttpClient
import com.minifile.explorer.core.peer.PeerHttpResponse
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Transfer ▸ Discoverable / Require Approval gates on `POST /api/peer/begin` (iOS `TransferView` parity). */
class HttpPeerReceivePolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: HttpFileServerEngine? = null
    private val client = PeerHttpClient(connectTimeoutMs = 3_000, soTimeoutMs = 5_000)
    private val tok = "0123456789abcdef0123456789abcdef"

    @After
    fun tearDown() {
        client.close()
        server?.stop()
        server = null
    }

    private fun start(
        gate: HotspotSessionGate = HotspotSessionGate(),
        allowed: () -> Boolean = { true },
        approver: ((PeerBeginOffer) -> Boolean)? = null,
    ): HttpFileServerEngine {
        val srv = HttpFileServerEngine(
            config = HttpConfig(
                port = 0,
                bindAddress = "127.0.0.1",
                root = VfsPath.Local(tempFolder.newFolder().canonicalPath),
            ),
            fs = LocalFileSystem(),
            hotspotGate = gate,
            peerReceiveAllowed = allowed,
            peerBeginApprover = approver,
        )
        srv.start()
        server = srv
        return srv
    }

    private fun begin(srv: HttpFileServerEngine, extra: String = ""): PeerHttpResponse =
        client.postEmpty("http://127.0.0.1:${srv.boundPort}/api/peer/begin?files=2&bytes=7&device=Phone$extra")

    private fun code(resp: PeerHttpResponse): String? =
        (MiniJson.parse(resp.bodyText) as JsonValue.Obj).str("code")

    @Test
    fun notDiscoverable_rejectsBegin_andTogglingBackAccepts() {
        val discoverable = AtomicBoolean(false)
        val srv = start(allowed = { discoverable.get() })
        val rejected = begin(srv)
        assertEquals(403, rejected.status)
        assertEquals("not_discoverable", code(rejected))

        discoverable.set(true)
        assertEquals(200, begin(srv).status)
    }

    @Test
    fun approver_receivesOffer_andDeclineRejects() {
        val seen = AtomicReference<PeerBeginOffer?>(null)
        val accept = AtomicBoolean(false)
        val srv = start(approver = { offer -> seen.set(offer); accept.get() })
        val declined = begin(srv)
        assertEquals(403, declined.status)
        assertEquals("declined", code(declined))
        assertEquals(PeerBeginOffer("127.0.0.1", "Phone", 2, 7L), seen.get())

        accept.set(true)
        assertEquals(200, begin(srv).status)
    }

    @Test
    fun tokenGatedSession_bypassesDiscoverableAndApproval() {
        val gate = HotspotSessionGate()
        gate.armSessionToken(tok)
        val asked = AtomicBoolean(false)
        val srv = start(gate = gate, allowed = { false }, approver = { asked.set(true); false })
        assertEquals(200, begin(srv, "&tok=$tok").status)
        assertTrue(!asked.get())
    }
}
