package com.minifile.explorer.ui.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Runtime permission matrix for Direct Hotspot / BLE / QR (spec §3.1):
 *
 * | Action          | API 26–28                     | API 29–32                 | API 33+                |
 * |-----------------|-------------------------------|---------------------------|------------------------|
 * | Start LOHS      | COARSE/FINE location + Loc ON | FINE location + Loc ON    | `NEARBY_WIFI_DEVICES`  |
 * | BLE             | FINE/COARSE + Loc ON (≤30)    | 31–32: SCAN/ADVERTISE/CONNECT | same as 31         |
 * | Join specifier  | CHANGE_WIFI_STATE (install)   | none                      | none                   |
 * | Scan QR         | CAMERA                        | CAMERA                    | CAMERA                 |
 */
object NearbyPermissions {

    /** Runtime permissions needed to start a LocalOnlyHotspot on this API level. */
    fun hotspotPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Runtime permissions needed for BLE scan + advertise + GATT on this API level. */
    fun blePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Everything the "Nearby Devices" + Direct Hotspot features may ask for in one prompt. */
    fun allNearbyPermissions(): Array<String> = (blePermissions() + hotspotPermissions()).distinct().toTypedArray()

    fun cameraPermission(): String = Manifest.permission.CAMERA

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Location group: either FINE or COARSE satisfies legacy APIs 26–28; 29+ needs FINE. */
    private fun hasLocation(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun hasHotspotPermissions(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            hasLocation(context)
        }

    fun hasBleScanPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasLocation(context)
        }

    fun hasBleAdvertisePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            true
        }

    fun hasBleConnectPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    fun hasAllBlePermissions(context: Context): Boolean =
        hasBleScanPermission(context) && hasBleAdvertisePermission(context) && hasBleConnectPermission(context)

    fun hasCamera(context: Context): Boolean = isGranted(context, Manifest.permission.CAMERA)

    /** Android ≤ 12 needs Location Services ON for BLE scans and LocalOnlyHotspot. */
    fun locationRequired(): Boolean = Build.VERSION.SDK_INT < 33

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE) !=
                    Settings.Secure.LOCATION_MODE_OFF
            }.getOrDefault(false)
        }
    }

    /** `true` when Location Services must be turned on before LOHS/BLE scanning can work. */
    fun needsLocationServices(context: Context): Boolean = locationRequired() && !isLocationEnabled(context)

    @SuppressLint("MissingPermission")
    fun isBluetoothOn(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return runCatching { bm.adapter?.isEnabled == true }.getOrDefault(false)
    }

    fun hasBleHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun bluetoothEnableIntent(): Intent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** Short human hint for what is missing (or `null` when everything is ready). */
    fun nearbyHint(context: Context): String? = when {
        !hasAllBlePermissions(context) -> "Allow Nearby devices to find phones without shared Wi-Fi"
        hasBleHardware(context) && !isBluetoothOn(context) -> "Turn on Bluetooth to find nearby phones"
        needsLocationServices(context) -> "Location must be on for Android ≤12 to start a hotspot / scan Bluetooth"
        else -> null
    }
}
