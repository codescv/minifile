package com.minifile.explorer.data

import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.model.SortField
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.ThemeMode
import com.minifile.explorer.core.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun persistsSettingsAcrossReloadAndAppliesTransientDebugFtpOverrides() {
        val store = InMemoryKeyValueStore()
        val repo1 = SettingsRepository(store)
        assertFalse(repo1.settings.value.showHidden)
        assertEquals(ViewMode.LIST, repo1.settings.value.viewMode)
        assertEquals(2121, repo1.settings.value.ftpPort)

        repo1.update {
            it.copy(
                showHidden = true,
                viewMode = ViewMode.GRID,
                sort = SortSpec(by = SortField.SIZE, ascending = false, foldersFirst = true),
                themeMode = ThemeMode.DARK,
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

        // Fresh process reload restores persisted values
        val repo2 = SettingsRepository(store)
        assertTrue(repo2.settings.value.showHidden)
        assertEquals(ViewMode.GRID, repo2.settings.value.viewMode)
        assertEquals(SortField.SIZE, repo2.settings.value.sort.by)
        assertFalse(repo2.settings.value.sort.ascending)
        assertEquals(ThemeMode.DARK, repo2.settings.value.themeMode)
        assertEquals(2222, repo2.settings.value.ftpPort)
        assertEquals(51000, repo2.settings.value.ftpPasvStart)
        assertEquals(51020, repo2.settings.value.ftpPasvEnd)
    }
}
