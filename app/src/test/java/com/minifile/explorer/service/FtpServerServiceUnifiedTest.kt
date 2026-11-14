package com.minifile.explorer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Transfer ▸ "HTTP & FTP Server": one switch starts / stops both servers (iOS `startFileSharing`). */
class FtpServerServiceUnifiedTest {

    @Test
    fun unifiedActions_startBothThenStopAll() {
        assertEquals(
            listOf(FtpServerService.ACTION_HTTP_START, FtpServerService.ACTION_START),
            FtpServerService.unifiedActions(start = true),
        )
        assertEquals(listOf(FtpServerService.ACTION_STOP_ALL), FtpServerService.unifiedActions(start = false))
    }

    @Test
    fun transferScreen_usesUnifiedSwitch_andDropsDirectHotspotCard() {
        var root: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (root != null && !(File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory)) {
            root = root.parentFile
        }
        val base = File(checkNotNull(root), "app/src/main/java/com/minifile/explorer")
        val screen = File(base, "ui/screens/WirelessScreen.kt").readText()
        assertTrue(screen.contains("FtpServerService.startBoth(context)"))
        assertTrue(screen.contains("FtpServerService.stopBoth(context)"))
        assertTrue(!screen.contains("DirectHotspotButtons("))
        assertTrue(!screen.contains("\"Direct Hotspot\""))
        // iOS section order: Nearby Devices → HTTP & FTP Server → Transfer Settings → Activity.
        val order = listOf("key = \"nearby-devices\"", "key = \"file-server\"", "key = \"transfer-settings\"", "key = \"activity\"")
        val idx = order.map { screen.indexOf(it) }
        assertTrue("sections missing: $idx", idx.all { it >= 0 })
        assertEquals(idx.sorted(), idx)
        assertTrue(screen.contains("viewModel.setDiscoverable("))
        assertTrue(screen.contains("\"Activity log (\${combinedLog.size})\""))

        val service = File(base, "service/FtpServerService.kt").readText()
        val launch = service.substringAfter("private fun launchHttpEngine()")
        assertTrue(launch.contains("if (settings.peerDiscoverable) {"))
        assertTrue(launch.contains("peerReceiveAllowed = { repo.settings.value.peerDiscoverable }"))
        val ble = File(base, "service/BlePeerCoordinator.kt").readText()
        assertTrue(ble.contains("if (isDiscoverable()) {"))
        assertTrue(ble.contains("if (!s.peerDiscoverable) {"))
        assertTrue(ble.contains("if (s.peerConfirmReceive) {"))
    }
}
