package com.minifile.explorer.service

import com.minifile.explorer.core.http.PeerBeginOffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PeerOfferApprovalsTest {

    private val offer = PeerBeginOffer(clientIp = "192.168.1.20", deviceName = "iPhone", files = 3, bytes = 1024L)

    @Test
    fun requireApprovalOff_autoAccepts_withoutPendingRequest() {
        val a = PeerOfferApprovals()
        assertTrue(a.requestApproval(offer, confirmRequired = false))
        assertNull(a.pendingRequest.value)
    }

    @Test
    fun accept_and_decline_unblockTheWaitingOffer() {
        val a = PeerOfferApprovals()
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (answer in listOf(true, false)) {
                val result = pool.submit<Boolean> { a.requestApproval(offer, confirmRequired = true) }
                val req = runBlocking { withTimeout(5_000) { a.pendingRequest.first { it != null } } }!!
                assertTrue(req.viaLan)
                assertEquals("iPhone", req.fromName)
                assertEquals(3, req.files)
                assertTrue(req.requestId > PeerOfferApprovals.REQUEST_ID_BASE)
                a.respond(req.requestId + 1, accept = !answer) // wrong id ignored
                a.respond(req.requestId, accept = answer)
                assertEquals(answer, result.get(5, TimeUnit.SECONDS))
                assertNull(a.pendingRequest.value)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun timeout_declines() {
        val a = PeerOfferApprovals(timeoutMs = 50L)
        assertFalse(a.requestApproval(offer, confirmRequired = true))
        assertNull(a.pendingRequest.value)
    }
}
