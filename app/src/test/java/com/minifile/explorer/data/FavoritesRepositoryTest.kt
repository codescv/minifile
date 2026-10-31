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

    @Test
    fun supportsFileFavoritesPersistenceExportImportAndLegacyDefaultTrue() = runBlocking {
        var now = 2_000_000L
        val store = InMemoryKeyValueStore()
        val repo1 = DefaultFavoritesRepository(
            store = store,
            clock = { now },
            defaultPrimaryRoot = "/storage/emulated/0",
        )

        val pdfPath = VfsPath.Local("/storage/emulated/0/Documents/spec.pdf")
        val ftpFilePath = VfsPath.Ftp("srv-1", "/docs/readme.md")

        val fileFav = repo1.add(
            name = "Project Spec",
            path = pdfPath,
            isDirectory = false,
        )
        assertFalse(fileFav.isDirectory)

        val ftpFav = repo1.add(
            name = "Remote Readme",
            path = ftpFilePath,
            isDirectory = false,
        )
        assertFalse(ftpFav.isDirectory)

        // Reload from store preserves isDirectory = false alongside folder favorites (isDirectory = true)
        val repo2 = DefaultFavoritesRepository(
            store = store,
            clock = { now },
            defaultPrimaryRoot = "/storage/emulated/0",
        )
        assertFalse(repo2.findByPath(pdfPath)!!.isDirectory)
        assertFalse(repo2.findByPath(ftpFilePath)!!.isDirectory)
        assertTrue(repo2.findByPath(VfsPath.Local("/storage/emulated/0/Download"))!!.isDirectory)

        // Export & import round-trip preserves isDirectory
        val exported = repo2.exportJson()
        val store3 = InMemoryKeyValueStore()
        val repo3 = DefaultFavoritesRepository(
            store = store3,
            clock = { now },
            defaultPrimaryRoot = "/storage/emulated/0",
        )
        repo3.importJson(exported, merge = false)
        assertFalse(repo3.findByPath(pdfPath)!!.isDirectory)
        assertFalse(repo3.findByPath(ftpFilePath)!!.isDirectory)
        assertTrue(repo3.findByPath(VfsPath.Local("/storage/emulated/0/Download"))!!.isDirectory)

        // Backward-compatibility: JSON without "isDirectory" defaults to true
        val legacyJson = """[{"id":"legacy-1","name":"Legacy Folder","path":"file:///storage/emulated/0/Music","order":0,"createdAt":100,"lastUsedAt":200}]"""
        repo3.importJson(legacyJson, merge = false)
        assertEquals(1, repo3.favorites.value.size)
        assertTrue(repo3.favorites.value.single().isDirectory)
    }
}
