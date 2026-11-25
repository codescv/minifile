package com.minifile.explorer.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level regression guards for the Android → iPhone `lohs-s` hand-off ("stuck on Sending to iPhone after the
 * iPhone joined the hotspot with the Camera"). These paths need real Bluetooth / LocalOnlyHotspot, so the wiring is
 * asserted on the sources; the pure-JVM pieces are covered by `ReannounceRetryTest`, `PeerHttpClientWriteStallTest`,
 * `HotspotSessionGateTest` and `HttpPeerHotspotTest`.
 */
class HotspotHandoffWiringTest {

    private val repoRoot: File by lazy {
        var root: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (root != null && !(File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory)) {
            root = root.parentFile
        }
        checkNotNull(root) { "Could not locate repository root" }
    }

    private fun src(rel: String) = File(repoRoot, "app/src/main/java/com/minifile/explorer/$rel").readText()

    private fun lohsS(): String = src("service/BlePeerCoordinator.kt")
        .substringAfter("suspend fun runLohsS(): LinkResult {")
        .substringBefore("when (plan.mode) {")

    @Test
    fun lohsS_subscribesToAnnounceBeforeSendingCredentials_andChecksWrites() {
        val body = lohsS()
        val subscribe = body.indexOf("startAnnounceWaiter(")
        val creds = body.indexOf("c.writeMessage(hotspotMessage(info, tok))")
        assertTrue("announce subscription must exist", subscribe >= 0)
        assertTrue("hotspot credentials must be sent", creds >= 0)
        assertTrue("subscribe before the credentials leave the phone", subscribe < creds)
        assertTrue(body.contains("val credsSent = c.writeMessage(HotspotLinkMessage.Link(LinkMode.LOHS_S)) &&"))
        assertTrue(body.contains("announced.cancel()"))
        assertTrue(body.contains("awaitHotspotHostIp(HOST_IP_WAIT_MS)"))
        assertTrue(body.contains("reannounces = reannouncesFrom(target2.id)"))
        val ble = src("service/BlePeerCoordinator.kt")
        assertTrue(ble.contains("scope.async(start = CoroutineStart.UNDISPATCHED)"))
        // Hotspot IP discovered after the credentials were sent ⇒ the `hotspot` message is re-sent.
        assertTrue(ble.contains("c.writeMessage(hotspotMessage(refined, tok))"))
    }

    @Test
    fun lohsS_copy_isAccurateAndEnglishOnly() {
        val body = lohsS()
        assertFalse("Accept already happened on the iPhone", body.contains("tap Accept"))
        assertTrue(body.contains("Scan this QR with the iPhone Camera to join, then return to Files on the iPhone"))
    }

    @Test
    fun linkDialogCancel_abortsTheHttpPhaseOfTheSend() {
        val ble = src("service/BlePeerCoordinator.kt")
        val cancel = ble.substringAfter("fun cancelLink() {").substringBefore("fun dismissLinkUi()")
        assertTrue(cancel.contains("activeSendAbort.getAndSet(null)?.let { abort -> runCatching { abort() } }"))

        val vm = src("ui/MainViewModel.kt")
        val send = vm.substringAfter("private suspend fun sendOverNegotiatedLink(").substringBefore("private fun releaseJoinedNetworkIfTarget(")
        assertTrue(send.contains("deps.setLinkSendAbort(onLinkCancel)"))
        assertTrue(send.contains("deps.setLinkSendAbort(null)"))
        assertTrue(send.contains("ReannounceRetry.run("))
        assertTrue(send.contains("abortActive = { client.cancelActive() }"))
        assertTrue(send.contains("val uiSink = deps.linkProgressSink(sink)"))
        assertTrue(vm.contains("runPeerSend(peer, sendItems, client, sink, bleFallback) { deps.operationRegistry.cancel(opId) }"))
        assertTrue(vm.contains("setLinkSendAbort = { container.blePeerCoordinator.setActiveSendAbort(it) }"))
        assertTrue(vm.contains("linkProgressSink = { container.blePeerCoordinator.linkProgressSink(it) }"))
    }

    @Test
    fun activeOperationsSheet_neverCoversTheLinkDialog() {
        val activity = src("MainActivity.kt")
        val block = activity.substringAfter("val primaryOpId = activeOps.firstOrNull()?.id")
            .substringBefore("// Operation completion")
        assertTrue(block.contains("val linkDialogShown = bleLinkUi != null"))
        assertTrue(block.contains("LaunchedEffect(primaryOpId, activeOps.isEmpty(), linkDialogShown)"))
        assertTrue(block.contains("} else if (linkDialogShown) {\n            showActiveOpsSheet = false"))
    }
}
