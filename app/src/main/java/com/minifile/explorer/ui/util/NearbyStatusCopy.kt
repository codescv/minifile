package com.minifile.explorer.ui.util

import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.AppStrings
import com.minifile.explorer.core.i18n.Str
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
 * Pure (no `android.*`) copy + decisions for the Nearby Devices / Send sheet UI.
 * All copy delegates to [AppI18n] so the Transfer tab and Send sheet render cleanly
 * in the active single UI language (`zh`, `en`, `ja`).
 */
object NearbyStatusCopy {
    val HOTSPOT_DIRECT_LABEL: String get() = AppI18n.current()[Str.HOTSPOT_DIRECT_LABEL]
    val SCAN_QR_LABEL: String get() = AppI18n.current()[Str.SCAN_QR_LABEL]
    val HOTSPOT_DIRECT_SUBTITLE: String get() = AppI18n.current()[Str.HOTSPOT_DIRECT_SUBTITLE]
    val SHOW_QR_INSTEAD: String get() = AppI18n.current()[Str.SHOW_QR_INSTEAD]

    // --- Transfer tab (1:1 with iOS `TransferView`) ---
    val DISCOVERABLE_LABEL: String get() = AppI18n.current()[Str.DISCOVERABLE_LABEL]
    val TRANSFER_EMPTY_DISCOVERABLE: String get() = AppI18n.current()[Str.TRANSFER_EMPTY_DISCOVERABLE]
    val TRANSFER_EMPTY_HIDDEN: String get() = AppI18n.current()[Str.TRANSFER_EMPTY_HIDDEN]
    val ADD_PEER_PLACEHOLDER: String get() = AppI18n.current()[Str.ADD_PEER_PLACEHOLDER]
    val NEARBY_FOOTER: String get() = AppI18n.current()[Str.NEARBY_FOOTER]
    val FILE_SERVER_TITLE: String get() = AppI18n.current()[Str.FILE_SERVER_TITLE]
    val FILE_SERVER_RUNNING: String get() = AppI18n.current()[Str.FILE_SERVER_RUNNING]
    val FILE_SERVER_FOOTER: String get() = AppI18n.current()[Str.FILE_SERVER_FOOTER]

    /** Transfer tab empty state for the Nearby Devices card (iOS copy when [discoverable]). */
    fun transferEmptyState(discoverable: Boolean, strings: AppStrings = AppI18n.current()): String =
        if (discoverable) strings[Str.TRANSFER_EMPTY_DISCOVERABLE] else strings[Str.TRANSFER_EMPTY_HIDDEN]

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

    fun hint(issue: NearbyAccessIssue, strings: AppStrings = AppI18n.current()): String = when (issue) {
        NearbyAccessIssue.PERMISSIONS -> strings[Str.NEARBY_HINT_PERMISSIONS]
        NearbyAccessIssue.BLUETOOTH_OFF -> strings[Str.NEARBY_HINT_BT_OFF]
        NearbyAccessIssue.LOCATION_OFF -> strings[Str.NEARBY_HINT_LOCATION_OFF]
    }

    fun actionLabel(issue: NearbyAccessIssue, strings: AppStrings = AppI18n.current()): String = when (issue) {
        NearbyAccessIssue.PERMISSIONS -> strings[Str.ALLOW]
        NearbyAccessIssue.BLUETOOTH_OFF -> strings[Str.TURN_ON]
        NearbyAccessIssue.LOCATION_OFF -> strings[Str.SETTINGS_LABEL]
    }

    /**
     * Caption for the "Nearby Devices" header. Starting HTTP Share is only suggested when Bluetooth discovery is
     * unsupported; when Bluetooth is merely off / not permitted the access row below the list explains the fix.
     */
    fun header(
        ble: BleAvailability,
        httpRunning: Boolean,
        readOnly: Boolean,
        strings: AppStrings = AppI18n.current(),
    ): NearbyHeaderStatus? = when {
        readOnly -> NearbyHeaderStatus(strings[Str.NEARBY_HEADER_RO], bluetooth = false)
        ble == BleAvailability.READY ->
            NearbyHeaderStatus(
                if (httpRunning) strings[Str.NEARBY_HEADER_BT_WIFI] else strings[Str.NEARBY_HEADER_BT_NO_QR],
                bluetooth = true,
            )
        httpRunning -> NearbyHeaderStatus(strings[Str.NEARBY_HEADER_WIFI], bluetooth = false)
        ble == BleAvailability.UNSUPPORTED -> NearbyHeaderStatus(strings[Str.NEARBY_HEADER_START_HTTP], bluetooth = false)
        else -> null
    }

    /** Empty-state text for the Transfer tab ([forSendSheet] = false) or the Send sheet. */
    fun emptyState(
        ble: BleAvailability,
        forSendSheet: Boolean,
        strings: AppStrings = AppI18n.current(),
    ): String = when (ble) {
        BleAvailability.READY ->
            if (forSendSheet) strings[Str.NEARBY_EMPTY_READY_SEND] else strings[Str.NEARBY_EMPTY_READY_TAB]
        BleAvailability.UNSUPPORTED ->
            if (forSendSheet) strings[Str.NEARBY_EMPTY_UNSUPPORTED_SEND] else strings[Str.NEARBY_EMPTY_UNSUPPORTED_TAB]
        else ->
            if (forSendSheet) strings[Str.NEARBY_EMPTY_OTHER_SEND] else strings[Str.NEARBY_EMPTY_OTHER_TAB]
    }

    /** Searching line shown above the empty state. */
    fun searching(
        ble: BleAvailability,
        lanScanRunning: Boolean,
        strings: AppStrings = AppI18n.current(),
    ): String = when {
        lanScanRunning -> strings[Str.NEARBY_SEARCHING_LAN]
        ble == BleAvailability.READY -> strings[Str.NEARBY_SEARCHING_BT_WIFI]
        else -> strings[Str.NEARBY_SEARCHING_DEFAULT]
    }
}
