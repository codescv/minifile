package com.minifile.explorer.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level regression guards for Android-only BLE wiring that cannot run on the JVM: foreground-wide receiver
 * presence, live radio-state reactivity, and the Android ↔ Android "no QR" receiver dialog.
 */
class BlePresenceWiringTest {

    private val repoRoot: File by lazy {
        var root: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (root != null && !(File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory)) {
            root = root.parentFile
        }
        checkNotNull(root) { "Could not locate repository root" }
    }

    private fun src(rel: String) = File(repoRoot, "app/src/main/java/com/minifile/explorer/$rel").readText()

    @Test
    fun lohsReceiver_showsQrOnlyForIphones() {
        val ble = src("service/BlePeerCoordinator.kt")
        val lohsR = ble.substringAfter("LinkMode.LOHS_R -> {\n                if (!canHostHotspot())")
            .substringBefore("LinkMode.LOHS_S ->")
        assertTrue(lohsR.contains("showQr = isIos,"))
        assertTrue(lohsR.contains("qrFallback = !isIos,"))
        assertFalse(lohsR.contains("showQr = true"))
        val dialog = src("ui/components/PeerConsentDialog.kt")
        assertTrue(dialog.contains("NearbyStatusCopy.SHOW_QR_INSTEAD"))
        assertTrue(dialog.contains("state.qrFallback"))
    }

    @Test
    fun presenceAndScanning_areSplitAndWiredToActivityLifecycle() {
        val ble = src("service/BlePeerCoordinator.kt")
        assertTrue(ble.contains("fun acquirePresence()"))
        assertTrue(ble.contains("fun releasePresence()"))
        assertTrue(ble.contains("fun acquire()"))
        assertTrue(ble.contains("fun release()"))
        assertTrue(ble.contains("BluetoothAdapter.ACTION_STATE_CHANGED"))
        assertTrue(ble.contains("LocationManager.MODE_CHANGED_ACTION"))
        assertTrue(ble.contains("ContextCompat.RECEIVER_NOT_EXPORTED"))
        assertTrue(ble.contains("HostDecision.capsFor(canHostHotspot())"))
        assertFalse(ble.contains("listOf(\"lohs\", \"join\")"))

        val activity = src("MainActivity.kt")
        val onStart = activity.substringAfter("override fun onStart()").substringBefore("override fun onStop()")
        assertTrue(onStart.contains("blePeerCoordinator.acquirePresence()"))
        val onStop = activity.substringAfter("override fun onStop()").substringBefore("override fun onResume()")
        assertTrue(onStop.contains("blePeerCoordinator.releasePresence()"))
        val onResume = activity.substringAfter("override fun onResume()").substringBefore("private fun handleLaunchIntent")
        assertTrue(onResume.contains("blePeerCoordinator.restartRadios()"))
    }

    @Test
    fun nearbyAccessRow_requiresHotspotPermissionAndRefreshesOnResume() {
        val sheet = src("ui/components/SendToDeviceSheet.kt")
        val row = sheet.substringAfter("fun NearbyAccessRow(").substringBefore("fun PeerDeviceRow(")
        assertTrue(row.contains("viewModel.bleAvailability.collectAsState()"))
        assertTrue(row.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(row.contains("NearbyPermissions.nearbyIssue(context)"))
        assertTrue(row.contains("NearbyPermissions.allNearbyPermissions()"))
        val perms = src("ui/util/NearbyPermissions.kt")
        assertTrue(perms.contains("hasAllBlePermissions(context) && hasHotspotPermissions(context)"))
    }
}
