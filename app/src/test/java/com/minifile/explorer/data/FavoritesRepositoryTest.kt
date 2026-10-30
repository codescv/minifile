package com.minifile.explorer.data

import com.minifile.explorer.core.model.InMemoryKeyValueStore
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesRepositoryTest {

    @Test
    fun seedsDefaultsAndSupportsAddRemoveRenameReorderTouchAndExportImport() = runBlocking {
        var now = 1_000_000L
        val store = InMemoryKeyValueStore()
        val repo = DefaultFavoritesRepository(
            store = store,
            clock = { now },
            defaultPrimaryRoot = "/storage/emulated/0",
        )

        // 3 seeded entries on first launch
        assertEquals(3, repo.favorites.value.size)
        assertEquals(listOf("Download", "Camera", "Documents"), repo.favorites.value.map { it.name })
        assertTrue(repo.isFavorite(VfsPath.Local("/storage/emulated/0/Download")))

        // Add SAF favorite
        now += 500
        val usbPath = VfsPath.Saf("content://usb/tree/1", "1234-5678:Docs")
        val added = repo.add("USB Docs", usbPath)
        assertEquals(4, repo.favorites.value.size)
        assertTrue(repo.isFavorite(usbPath))

        // Rename & touch
        now += 500
        repo.rename(added.id, "SanDisk Docs")
        repo.touch(added.id)
        val updated = repo.findByPath(usbPath)!!
        assertEquals("SanDisk Docs", updated.name)
        assertEquals(now, updated.lastUsedAt)

        // Reorder so SanDisk Docs is first
        val reversedIds = repo.favorites.value.map { it.id }.reversed()
        repo.reorder(reversedIds)
        assertEquals("SanDisk Docs", repo.favorites.value.first().name)
        assertEquals(listOf(0, 1, 2, 3), repo.favorites.value.map { it.order })

        // Export JSON, wipe, and import
        val exported = repo.exportJson()
        repo.remove(added.id)
        assertFalse(repo.isFavorite(usbPath))

        repo.importJson(exported, merge = false)
        assertEquals(4, repo.favorites.value.size)
        assertTrue(repo.isFavorite(usbPath))
    }
}
