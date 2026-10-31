package com.minifile.explorer.data

import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.model.SortField
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.ThemeMode
import com.minifile.explorer.core.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun persistsSettingsAcrossReloadAndAppliesTransientDebugFtpOverrides() {
        val store = InMemoryKeyValueStore()
        val repo1 = SettingsRepository(store)
        assertFalse(repo1.settings.value.showHidden)
        assertEquals(ViewMode.LIST, repo1.settings.value.viewMode)
        assertEquals(8080, repo1.settings.value.httpPort)
        assertNull(repo1.settings.value.httpRoot)
        assertFalse(repo1.settings.value.httpReadOnly)
        assertEquals(2121, repo1.settings.value.ftpPort)

        repo1.update {
            it.copy(
                showHidden = true,
                viewMode = ViewMode.GRID,
                sort = SortSpec(by = SortField.SIZE, ascending = false, foldersFirst = true),
                themeMode = ThemeMode.DARK,
                httpPort = 8888,
                httpRoot = "file:///storage/emulated/0/Download",
                httpReadOnly = true,
                ftpPort = 2222,
                ftpPasvStart = 51000,
                ftpPasvEnd = 51020,
            )
        }

        // Transient debug override changes in-memory view without overwriting persisted prefs
        repo1.setDebugFtpOverrides(port = 12121, pasvMin = 50000, pasvMax = 50004)
        assertEquals(12121, repo1.settings.value.ftpPort)
        assertEquals(50000, repo1.settings.value.ftpPasvStart)
        assertEquals(50004, repo1.settings.value.ftpPasvEnd)

        // Updating unrelated settings must preserve active debug overrides
        repo1.update { it.copy(onboardingDone = true, updateLastCheckMs = 999L) }
        assertTrue(repo1.settings.value.onboardingDone)
        assertEquals(12121, repo1.settings.value.ftpPort)
        assertEquals(50000, repo1.settings.value.ftpPasvStart)
        assertEquals(50004, repo1.settings.value.ftpPasvEnd)

        // Fresh process reload restores persisted values
        val repo2 = SettingsRepository(store)
        assertTrue(repo2.settings.value.showHidden)
        assertEquals(ViewMode.GRID, repo2.settings.value.viewMode)
        assertEquals(SortField.SIZE, repo2.settings.value.sort.by)
        assertFalse(repo2.settings.value.sort.ascending)
        assertEquals(ThemeMode.DARK, repo2.settings.value.themeMode)
        assertEquals(8888, repo2.settings.value.httpPort)
        assertEquals("file:///storage/emulated/0/Download", repo2.settings.value.httpRoot)
        assertTrue(repo2.settings.value.httpReadOnly)
        assertEquals(2222, repo2.settings.value.ftpPort)
        assertEquals(51000, repo2.settings.value.ftpPasvStart)
        assertEquals(51020, repo2.settings.value.ftpPasvEnd)
    }

    @Test
    fun peerDeviceIdAndDeviceName_generatedOncePersistedAndSanitized() {
        val store = InMemoryKeyValueStore()
        val repo1 = SettingsRepository(store, defaultDeviceNameProvider = { "Pixel\u0007 9 Pro  " })

        val generatedId = repo1.settings.value.peerDeviceId
        assertTrue("Expected non-blank UUID peerDeviceId", generatedId.length >= 16)
        assertEquals("Pixel 9 Pro", repo1.settings.value.peerDeviceName)

        // Reloading against the same store preserves the generated peerDeviceId
        val repo2 = SettingsRepository(store, defaultDeviceNameProvider = { "Different Default" })
        assertEquals(generatedId, repo2.settings.value.peerDeviceId)
        assertEquals("Pixel 9 Pro", repo2.settings.value.peerDeviceName)

        // Updating device name sanitizes control chars and persists
        repo2.setPeerDeviceName("  Chii's\n Fold  ")
        assertEquals("Chii's Fold", repo2.settings.value.peerDeviceName)

        val repo3 = SettingsRepository(store)
        assertEquals(generatedId, repo3.settings.value.peerDeviceId)
        assertEquals("Chii's Fold", repo3.settings.value.peerDeviceName)
    }
}
