package com.minifile.explorer.ui.util

import com.minifile.explorer.service.BleAvailability

/** What blocks Bluetooth QR-free nearby transfer, in the order the user should fix it. */
enum class NearbyAccessIssue {
    /** `BLUETOOTH_*` and/or `NEARBY_WIFI_DEVICES` (Location on Android ≤ 12) not granted. */
    PERMISSIONS,
    BLUETOOTH_OFF,

    /** Android ≤ 12 only: Location Services must be on for BLE scans and LocalOnlyHotspot. */
    LOCATION_OFF,
}

/** Header caption beside "Nearby Devices"; [bluetooth] shows the Bluetooth glyph. */
data class NearbyHeaderStatus(val text: String, val bluetooth: Boolean)

/**
 * Pure (no `android.*`) copy + decisions for the Nearby Devices / Send sheet UI so the strings that tell users
 * "Bluetooth QR-free transfer exists" are unit-testable. Transfer UI copy is English-only.
 */
object NearbyStatusCopy {
    const val HOTSPOT_DIRECT_LABEL = "Hotspot Direct"
    const val SCAN_QR_LABEL = "Scan QR"
    const val HOTSPOT_DIRECT_SUBTITLE = "No router or mobile data needed"
    const val SHOW_QR_INSTEAD = "Show QR code instead"

    fun issue(
        hasBleHardware: Boolean,
        hasAllNearbyPermissions: Boolean,
        bluetoothOn: Boolean,
        needsLocationServices: Boolean,
    ): NearbyAccessIssue? = when {
        !hasBleHardware -> null
        !hasAllNearbyPermissions -> NearbyAccessIssue.PERMISSIONS
        !bluetoothOn -> NearbyAccessIssue.BLUETOOTH_OFF
        needsLocationServices -> NearbyAccessIssue.LOCATION_OFF
        else -> null
    }

    fun hint(issue: NearbyAccessIssue): String = when (issue) {
        NearbyAccessIssue.PERMISSIONS -> "Allow Bluetooth & Nearby Wi-Fi for QR-free nearby transfer"
        NearbyAccessIssue.BLUETOOTH_OFF -> "Turn on Bluetooth for QR-free nearby discovery"
        NearbyAccessIssue.LOCATION_OFF -> "Turn on Location for Bluetooth & hotspot discovery"
    }

    fun actionLabel(issue: NearbyAccessIssue): String = when (issue) {
        NearbyAccessIssue.PERMISSIONS -> "Allow"
        NearbyAccessIssue.BLUETOOTH_OFF -> "Turn on"
        NearbyAccessIssue.LOCATION_OFF -> "Settings"
    }

    /**
     * Caption for the "Nearby Devices" header. Starting HTTP Share is only suggested when Bluetooth discovery is
     * unsupported; when Bluetooth is merely off / not permitted the access row below the list explains the fix.
     */
    fun header(ble: BleAvailability, httpRunning: Boolean, readOnly: Boolean): NearbyHeaderStatus? = when {
        readOnly -> NearbyHeaderStatus("Read-only on", bluetooth = false)
        ble == BleAvailability.READY ->
            NearbyHeaderStatus(if (httpRunning) "Bluetooth + Wi-Fi ready" else "Bluetooth ready · No QR", bluetooth = true)
        httpRunning -> NearbyHeaderStatus("Wi-Fi ready", bluetooth = false)
        ble == BleAvailability.UNSUPPORTED -> NearbyHeaderStatus("Start HTTP Share to be discoverable", bluetooth = false)
        else -> null
    }

    /** Empty-state text for the Transfer tab ([forSendSheet] = false) or the Send sheet. */
    fun emptyState(ble: BleAvailability, forSendSheet: Boolean): String {
        val alt = if (forSendSheet) "or use Hotspot Direct / Scan QR below" else "or start HTTP Share for LAN"
        return when (ble) {
            BleAvailability.READY ->
                "No devices yet — open Files on the other phone to discover via Bluetooth (no QR or shared Wi-Fi needed), $alt."
            BleAvailability.UNSUPPORTED ->
                if (forSendSheet) {
                    "No devices yet — open Files ▸ Transfer on the other phone and start HTTP Share, $alt."
                } else {
                    "No devices yet — on the other phone open Transfer and start HTTP Share."
                }
            else ->
                "No devices yet — enable Bluetooth discovery below for QR-free transfer, $alt."
        }
    }

    /** Searching line shown above the empty state. */
    fun searching(ble: BleAvailability, lanScanRunning: Boolean): String = when {
        lanScanRunning -> "Scanning local network…"
        ble == BleAvailability.READY -> "Looking for nearby devices via Bluetooth & Wi-Fi…"
        else -> "Looking for nearby devices…"
    }
}
