package com.minifile.explorer.ui.util

import com.minifile.explorer.service.BleAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NearbyStatusCopyTest {

    private val cjk = Regex("[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]")

    @Test
    fun issue_ordersPermissionsThenBluetoothThenLocation() {
        assertNull(NearbyStatusCopy.issue(hasBleHardware = false, hasAllNearbyPermissions = false, bluetoothOn = false, needsLocationServices = true))
        assertEquals(
            NearbyAccessIssue.PERMISSIONS,
            NearbyStatusCopy.issue(hasBleHardware = true, hasAllNearbyPermissions = false, bluetoothOn = false, needsLocationServices = true),
        )
        assertEquals(
            NearbyAccessIssue.BLUETOOTH_OFF,
            NearbyStatusCopy.issue(hasBleHardware = true, hasAllNearbyPermissions = true, bluetoothOn = false, needsLocationServices = true),
        )
        assertEquals(
            NearbyAccessIssue.LOCATION_OFF,
            NearbyStatusCopy.issue(hasBleHardware = true, hasAllNearbyPermissions = true, bluetoothOn = true, needsLocationServices = true),
        )
        assertNull(NearbyStatusCopy.issue(hasBleHardware = true, hasAllNearbyPermissions = true, bluetoothOn = true, needsLocationServices = false))
    }

    @Test
    fun hintsAndActions_matchSpec() {
        assertEquals("Allow Bluetooth & Nearby Wi-Fi for QR-free nearby transfer", NearbyStatusCopy.hint(NearbyAccessIssue.PERMISSIONS))
        assertEquals("Allow", NearbyStatusCopy.actionLabel(NearbyAccessIssue.PERMISSIONS))
        assertEquals("Turn on Bluetooth for QR-free nearby discovery", NearbyStatusCopy.hint(NearbyAccessIssue.BLUETOOTH_OFF))
        assertEquals("Turn on", NearbyStatusCopy.actionLabel(NearbyAccessIssue.BLUETOOTH_OFF))
        assertEquals("Turn on Location for Bluetooth & hotspot discovery", NearbyStatusCopy.hint(NearbyAccessIssue.LOCATION_OFF))
        assertEquals("Settings", NearbyStatusCopy.actionLabel(NearbyAccessIssue.LOCATION_OFF))
    }

    @Test
    fun header_surfacesBluetoothQrFreeWhenReady() {
        assertEquals(
            NearbyHeaderStatus("Bluetooth ready · No QR", bluetooth = true),
            NearbyStatusCopy.header(BleAvailability.READY, httpRunning = false, readOnly = false),
        )
        assertEquals(
            NearbyHeaderStatus("Bluetooth + Wi-Fi ready", bluetooth = true),
            NearbyStatusCopy.header(BleAvailability.READY, httpRunning = true, readOnly = false),
        )
        assertEquals(
            NearbyHeaderStatus("Read-only on", bluetooth = false),
            NearbyStatusCopy.header(BleAvailability.READY, httpRunning = true, readOnly = true),
        )
    }

    @Test
    fun header_onlySuggestsHttpShareWhenBluetoothUnsupported() {
        assertEquals(
            "Start HTTP Share to be discoverable",
            NearbyStatusCopy.header(BleAvailability.UNSUPPORTED, httpRunning = false, readOnly = false)?.text,
        )
        for (a in listOf(BleAvailability.BLUETOOTH_OFF, BleAvailability.NO_PERMISSION, BleAvailability.LOCATION_OFF)) {
            val h = NearbyStatusCopy.header(a, httpRunning = false, readOnly = false)
            assertNull("$a", h)
            assertEquals("Wi-Fi ready", NearbyStatusCopy.header(a, httpRunning = true, readOnly = false)?.text)
        }
        for (a in BleAvailability.entries) {
            val h = NearbyStatusCopy.header(a, httpRunning = false, readOnly = false)
            if (a != BleAvailability.UNSUPPORTED) assertFalse(h?.text.orEmpty().contains("HTTP Share"))
        }
    }

    @Test
    fun emptyState_explainsBluetoothQrFreeTransfer() {
        val ready = NearbyStatusCopy.emptyState(BleAvailability.READY, forSendSheet = false)
        assertEquals(
            "No devices yet — open Files on the other phone to discover via Bluetooth (no QR or shared Wi-Fi needed), " +
                "or start HTTP Share for LAN.",
            ready,
        )
        assertTrue(NearbyStatusCopy.emptyState(BleAvailability.READY, forSendSheet = true).contains("Bluetooth"))
        assertTrue(NearbyStatusCopy.emptyState(BleAvailability.BLUETOOTH_OFF, forSendSheet = false).contains("enable Bluetooth"))
        assertTrue(NearbyStatusCopy.emptyState(BleAvailability.UNSUPPORTED, forSendSheet = false).contains("HTTP Share"))
        assertEquals("Scanning local network…", NearbyStatusCopy.searching(BleAvailability.READY, lanScanRunning = true))
        assertTrue(NearbyStatusCopy.searching(BleAvailability.READY, lanScanRunning = false).contains("Bluetooth"))
    }

    @Test
    fun allCopy_isEnglishOnly() {
        val strings = buildList {
            add(NearbyStatusCopy.HOTSPOT_DIRECT_LABEL)
            add(NearbyStatusCopy.SCAN_QR_LABEL)
            add(NearbyStatusCopy.HOTSPOT_DIRECT_SUBTITLE)
            add(NearbyStatusCopy.SHOW_QR_INSTEAD)
            NearbyAccessIssue.entries.forEach { add(NearbyStatusCopy.hint(it)); add(NearbyStatusCopy.actionLabel(it)) }
            for (a in BleAvailability.entries) {
                add(NearbyStatusCopy.emptyState(a, true))
                add(NearbyStatusCopy.emptyState(a, false))
                add(NearbyStatusCopy.searching(a, false))
                for (http in listOf(true, false)) for (ro in listOf(true, false)) {
                    NearbyStatusCopy.header(a, http, ro)?.let { add(it.text) }
                }
            }
        }
        for (s in strings) assertFalse("CJK in \"$s\"", cjk.containsMatchIn(s))
        assertEquals("Hotspot Direct", NearbyStatusCopy.HOTSPOT_DIRECT_LABEL)
        assertEquals("Scan QR", NearbyStatusCopy.SCAN_QR_LABEL)
    }

    /** Regression (v1.0.18 feedback: no Chinese captions on Hotspot Direct / Scan QR): transfer UI stays English-only. */
    @Test
    fun transferUiSources_containNoChinese() {
        var root: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (root != null && !(File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory)) {
            root = root.parentFile
        }
        val repoRoot = checkNotNull(root) { "Could not locate repository root" }
        val base = "app/src/main/java/com/minifile/explorer"
        val files = listOf(
            "$base/ui/components/SendToDeviceSheet.kt",
            "$base/ui/components/HotspotDirectSheet.kt",
            "$base/ui/components/PeerConsentDialog.kt",
            "$base/ui/components/QrScannerSheet.kt",
            "$base/ui/screens/WirelessScreen.kt",
            "$base/ui/util/NearbyPermissions.kt",
            "$base/ui/util/NearbyStatusCopy.kt",
            "$base/service/BlePeerCoordinator.kt",
            "$base/service/LocalOnlyHotspotManager.kt",
            "$base/service/WifiSpecifierConnector.kt",
            "$base/core/peer/HotspotLinkModels.kt",
            "$base/ui/components/JoinHotspotDialog.kt",
            "$base/ui/util/SystemWifiJoin.kt",
            "$base/service/PeerDiscoveryHolder.kt",
            "$base/core/peer/PeerModels.kt",
            "$base/core/peer/PeerTransferEngine.kt",
            "$base/core/peer/PeerSendFallback.kt",
            "$base/core/peer/PeerAnnounceWire.kt",
            "$base/core/net/LocalSubnetRouter.kt",
            "$base/core/net/GatewayCandidates.kt",
        )
        for (rel in files) {
            val f = File(repoRoot, rel)
            if (!f.isFile) continue
            f.readLines().forEachIndexed { i, line ->
                assertFalse("Chinese text in $rel:${i + 1}: $line", cjk.containsMatchIn(line))
            }
        }
        val sheet = File(repoRoot, "$base/ui/components/SendToDeviceSheet.kt").readText()
        val buttons = sheet.substringAfter("fun DirectHotspotButtons(").substringBefore("fun NearbyAccessRow(")
        assertTrue(buttons.contains("NearbyStatusCopy.HOTSPOT_DIRECT_LABEL"))
        assertTrue(buttons.contains("NearbyStatusCopy.SCAN_QR_LABEL"))
        assertTrue(buttons.contains("maxLines = 1"))
        assertTrue(buttons.contains("TextOverflow.Ellipsis"))
        assertFalse("DirectHotspotButtons must be single-line (no caption Column)", buttons.contains("Column {"))
        assertNotNull(buttons)
    }
}
