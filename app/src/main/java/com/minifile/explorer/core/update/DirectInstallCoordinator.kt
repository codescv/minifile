package com.minifile.explorer.core.update

import java.io.File

/**
 * Next step decided by [DirectInstallCoordinator] (`v1.0.16`: tapping `Download & Install` installs
 * directly after the SHA-256 + signature verified APK is ready, without a second tap).
 */
sealed interface DirectInstallAction {
    /** Nothing to launch (no pending APK, or permission still not granted). */
    data object None : DirectInstallAction

    /** Launch the system package installer for [apkFile] right away. */
    data class LaunchInstaller(val apkFile: File) : DirectInstallAction

    /**
     * Unknown-sources permission is missing: open `ACTION_MANAGE_UNKNOWN_APP_SOURCES`; [apkFile] stays
     * pending and is installed automatically once the user grants permission and returns to the app.
     */
    data class RequestInstallPermission(val apkFile: File) : DirectInstallAction
}

/**
 * Pure (Android-free, unit-testable) state machine for the "download → verify → install directly" flow.
 *
 * - [onApkVerified]: called as soon as the SHA-256 + signature verified APK is ready (or when the user
 *   re-taps `Install update`). Launches the installer immediately when allowed, otherwise records the APK
 *   as pending and asks for the unknown-sources permission.
 * - [onAppForegrounded]: called from `MainActivity.onResume`. If an APK is pending and the permission is
 *   now granted, clears the pending flag and launches the installer automatically.
 */
class DirectInstallCoordinator {

    @Volatile
    var pendingInstallApkFile: File? = null
        private set

    val pendingInstallAfterSettings: Boolean
        get() = pendingInstallApkFile != null

    fun onApkVerified(apkFile: File, canRequestPackageInstalls: Boolean): DirectInstallAction {
        return if (canRequestPackageInstalls) {
            pendingInstallApkFile = null
            DirectInstallAction.LaunchInstaller(apkFile)
        } else {
            pendingInstallApkFile = apkFile
            DirectInstallAction.RequestInstallPermission(apkFile)
        }
    }

    fun onAppForegrounded(canRequestPackageInstalls: Boolean): DirectInstallAction {
        val pending = pendingInstallApkFile ?: return DirectInstallAction.None
        if (!canRequestPackageInstalls) return DirectInstallAction.None
        pendingInstallApkFile = null
        return if (pending.isFile) DirectInstallAction.LaunchInstaller(pending) else DirectInstallAction.None
    }

    fun clearPending() {
        pendingInstallApkFile = null
    }
}
