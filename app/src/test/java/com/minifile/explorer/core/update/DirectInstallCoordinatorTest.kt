package com.minifile.explorer.core.update

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `v1.0.16`: tapping `Download & Install` installs directly after SHA-256 + signature verification. */
class DirectInstallCoordinatorTest {

    private lateinit var dir: File
    private lateinit var apk: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("direct-install").toFile()
        apk = File(dir, "minifile-1.0.16.apk").apply { writeBytes(byteArrayOf(0x50, 0x4b, 3, 4)) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun verifiedApk_withInstallPermission_launchesInstallerImmediately() {
        val c = DirectInstallCoordinator()
        assertEquals(DirectInstallAction.LaunchInstaller(apk), c.onApkVerified(apk, canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertNull(c.pendingInstallApkFile)
        // Returning to foreground afterwards must not relaunch the installer.
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun verifiedApk_withoutPermission_requestsPermissionAndInstallsAfterGrant() {
        val c = DirectInstallCoordinator()
        assertEquals(
            DirectInstallAction.RequestInstallPermission(apk),
            c.onApkVerified(apk, canRequestPackageInstalls = false),
        )
        assertTrue(c.pendingInstallAfterSettings)
        assertEquals(apk, c.pendingInstallApkFile)

        // User returns without granting: stays pending, nothing launched.
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = false))
        assertTrue(c.pendingInstallAfterSettings)

        // User grants and returns: installer auto-launches exactly once.
        assertEquals(DirectInstallAction.LaunchInstaller(apk), c.onAppForegrounded(canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun foregroundWithoutPendingApk_doesNothing() {
        val c = DirectInstallCoordinator()
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = false))
    }

    @Test
    fun pendingApkDeletedWhileInSettings_isClearedWithoutLaunching() {
        val c = DirectInstallCoordinator()
        c.onApkVerified(apk, canRequestPackageInstalls = false)
        apk.delete()
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
    }

    @Test
    fun manualRetap_afterPermissionGranted_clearsPendingAndLaunches() {
        val c = DirectInstallCoordinator()
        c.onApkVerified(apk, canRequestPackageInstalls = false)
        // User re-taps `立即安装更新` after granting permission elsewhere.
        assertEquals(DirectInstallAction.LaunchInstaller(apk), c.onApkVerified(apk, canRequestPackageInstalls = true))
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun clearPending_dropsPendingInstall() {
        val c = DirectInstallCoordinator()
        c.onApkVerified(apk, canRequestPackageInstalls = false)
        c.clearPending()
        assertFalse(c.pendingInstallAfterSettings)
        assertEquals(DirectInstallAction.None, c.onAppForegrounded(canRequestPackageInstalls = true))
    }

    @Test
    fun toolsScreenAndLifecycleWireDirectInstallAndResume() {
        var root: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (root != null && !(File(root, "settings.gradle.kts").isFile && File(root, "app").isDirectory)) {
            root = root.parentFile
        }
        val repoRoot = checkNotNull(root) { "Could not locate repository root" }
        val tools = File(repoRoot, "app/src/main/java/com/minifile/explorer/ui/screens/ToolsScreen.kt").readText()
        val mainActivity = File(repoRoot, "app/src/main/java/com/minifile/explorer/MainActivity.kt").readText()
        val updateManager = File(repoRoot, "app/src/main/java/com/minifile/explorer/data/update/UpdateManager.kt").readText()

        // One tap on "Download & Install" downloads + verifies, then installs without a second tap
        assertTrue(tools.contains("\"Download & Install\""))
        assertTrue(tools.contains("onReadyToInstall = { triggerInstall() }"))
        // Re-trigger button stays in Ready state if the user dismissed the system installer dialog
        assertTrue(tools.contains("\"Install update\""))
        assertTrue(tools.contains("is UpdateUiState.Ready -> triggerInstall()"))
        // Unknown-sources settings opened directly (no extra confirmation dialog) + resume auto-install
        assertTrue(mainActivity.contains("runCatching { context.startActivity(intent) }"))
        assertFalse(mainActivity.contains("unknownSourcesIntent"))
        assertTrue(mainActivity.contains("container.updateManager.onHostResume(this)"))
        assertTrue(updateManager.contains("directInstallCoordinator.onApkVerified("))
        assertTrue(updateManager.contains("directInstallCoordinator.onAppForegrounded("))
        // SHA-256 from SHA256SUMS.txt is passed to the download
        assertTrue(updateManager.contains("expectedSha256 = current.expectedSha256"))
    }
}
