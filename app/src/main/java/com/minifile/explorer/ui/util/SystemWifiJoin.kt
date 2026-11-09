package com.minifile.explorer.ui.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import com.minifile.explorer.service.WifiSpecifierConnector

/**
 * System Wi-Fi fallback for a scanned `WIFI:` QR when the in-app `WifiNetworkSpecifier` join is rejected (some
 * OxygenOS / ColorOS builds): Android 11+ "Add network" sheet (`Settings.ACTION_WIFI_ADD_NETWORKS`) pre-filled with
 * the SSID / password, else the Wi-Fi settings screen. Once the phone joins, `PeerDiscoveryHolder`'s gateway prober
 * finds the Files host automatically when the user returns to Files.
 */
object SystemWifiJoin {
    /** `Settings.ACTION_WIFI_ADD_NETWORKS` exists on Android 11+ (API 30). */
    val isAddNetworkSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Opens the system "Add network" sheet (or Wi-Fi settings). Returns `false` when nothing could be opened. */
    fun launch(context: Context, ssid: String, password: String, sec: String): Boolean {
        if (isAddNetworkSupported) {
            val ok = runCatching {
                val suggestion = buildSuggestion(ssid, password, sec)
                if (suggestion != null) {
                    val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                        putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
                        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return runCatching {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** Copies the Wi-Fi password, flagged sensitive so Android 13+ hides it from the clipboard preview. */
    fun copyPassword(context: Context, password: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val clip = ClipData.newPlainText("Wi-Fi password", password)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        true
    }.getOrDefault(false)

    private fun buildSuggestion(ssid: String, password: String, sec: String): WifiNetworkSuggestion? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val s = sec.trim().lowercase()
        if (s == "wep") return null // WifiNetworkSuggestion has no WEP support: use Wi-Fi settings instead
        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        when (WifiSpecifierConnector.securityAttemptOrder(sec, password).firstOrNull()) {
            WifiSpecifierConnector.SEC_OPEN -> Unit
            WifiSpecifierConnector.SEC_WPA3 -> builder.setWpa3Passphrase(password)
            else -> builder.setWpa2Passphrase(password)
        }
        return builder.build()
    }
}
