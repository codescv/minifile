package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.vfs.FakeFileSystem
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GalleryRepositoryReconcileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeIndexSource(
        private val items: List<MediaItem>,
    ) : MediaIndexSource {
        override suspend fun fetchFastHead(scope: GalleryScope, limit: Int): List<MediaItem> = items.take(limit)
        override suspend fun fetchAll(scope: GalleryScope, includeHidden: Boolean): List<MediaItem> = items
        override suspend fun fetchRecent(limit: Int): List<MediaItem> = items.take(limit)
    }

    @Test
    fun localFolderReconcile_prunesGhostsAddsPendingIndexAndRescansDiffs() = runBlocking {
        val dcim = tempFolder.newFolder("DCIM")
        val cam = File(dcim, "Camera").apply { mkdirs() }
        val indexedFile = File(cam, "IMG_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unindexedFile = File(cam, "IMG_2_new.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val nonMediaDoc = File(cam, "notes.txt").apply { writeText("keep me visible") }
        val ghostPath = VfsPath.Local(File(cam, "IMG_ghost.jpg").absolutePath)

        val indexedPath = VfsPath.Local(indexedFile.absolutePath)
        val unindexedPath = VfsPath.Local(unindexedFile.absolutePath)
        assertTrue(nonMediaDoc.isFile)

        val msItems = listOf(
            MediaItem(
                path = indexedPath,
                name = "IMG_1.jpg",
                kind = MediaKind.IMAGE,
                mime = "image/jpeg",
                sizeBytes = 3L,
                bestDateMs = 1_700_000_000_000L,
                modifiedMs = 1_700_000_000_000L,
                width = 4000,
                height = 3000,
                storeId = 101L,
            ),
            MediaItem(
                path = ghostPath,
                name = "IMG_ghost.jpg",
                kind = MediaKind.IMAGE,
                mime = "image/jpeg",
                sizeBytes = 99L,
                bestDateMs = 1_700_000_005_000L,
                modifiedMs = 1_700_000_005_000L,
                storeId = 102L,
            ),
        )

        val rescanned = mutableListOf<VfsPath>()
        val notifier = object : MediaScanNotifier {
            override fun rescan(path: VfsPath) {
                rescanned.add(path)
            }

            override fun rescanFiles(paths: List<VfsPath>) {
                rescanned.addAll(paths)
            }
        }

        val repo = GalleryRepository(
            fs = FileSystemRepository(listOf(LocalFileSystem())),
            primaryIndexSource = FakeIndexSource(msItems),
            mediaScanNotifier = notifier,
        )

        val snap = repo.loadAndReconcile(GalleryScope.Folder(VfsPath.Local(cam.absolutePath), includeSubtree = true))
        assertEquals(2, snap.items.size)
        assertEquals(1, snap.reconciledAddedCount)
        assertEquals(1, snap.reconciledGhostCount)
        assertEquals(1, snap.nonMediaDirectCount)

        // Ghost pruned, unindexed added with PENDING_INDEX, indexed preserves MediaStore metadata
        assertFalse(snap.items.any { it.path == ghostPath })
        val mergedIndexed = snap.items.first { it.path == indexedPath }
        assertEquals(101L, mergedIndexed.storeId)
        assertEquals(4000, mergedIndexed.width)

        val mergedNew = snap.items.first { it.path == unindexedPath }
        assertTrue(mergedNew.hasFlag(MediaItemFlags.PENDING_INDEX))

        // Both ghost and unindexed paths triggered rescanFiles
        assertTrue(ghostPath in rescanned)
        assertTrue(unindexedPath in rescanned)

        // Optimistic delete removes immediately and rescans
        rescanned.clear()
        val afterDel = repo.applyOptimisticMutation(GalleryMutation.Removed(listOf(indexedPath)))
        assertEquals(1, afterDel!!.items.size)
        assertEquals(listOf(indexedPath), rescanned)
    }

    @Test
    fun nomediaSubfolder_isIsolatedFromParentAndDirectlyListableWithFlag() = runBlocking {
        val fakeFs = FakeFileSystem()
        val dcim = fakeFs.createDirectory(fakeFs.rootPath(), "DCIM")
        val cam = fakeFs.createDirectory(dcim, "Camera")
        val hiddenSub = fakeFs.createDirectory(dcim, "Secret")

        val pubImg = fakeFs.createFile(cam, "pub.jpg", "image/jpeg")
        fakeFs.openOutput(pubImg).use { it.write(byteArrayOf(1, 2)) }

        fakeFs.createFile(hiddenSub, ".nomedia", "application/octet-stream")
        val secretImg = fakeFs.createFile(hiddenSub, "priv.jpg", "image/jpeg")
        fakeFs.openOutput(secretImg).use { it.write(byteArrayOf(3, 4)) }

        val repo = GalleryRepository(fs = fakeFs)

        // Parent DCIM subtree scan MUST exclude Secret/.nomedia
        val parentSnap = repo.loadAndReconcile(GalleryScope.Folder(dcim, includeSubtree = true))
        assertEquals(listOf(pubImg), parentSnap.items.map { it.path })
        assertFalse(parentSnap.hiddenByNomedia)

        // Direct open of Secret lists priv.jpg and marks HIDDEN_BY_NOMEDIA
        val secretSnap = repo.loadAndReconcile(GalleryScope.Folder(hiddenSub, includeSubtree = false))
        assertEquals(listOf(secretImg), secretSnap.items.map { it.path })
        assertTrue(secretSnap.hiddenByNomedia)
        assertTrue(secretSnap.items.single().hasFlag(MediaItemFlags.HIDDEN_BY_NOMEDIA))
    }

    @Test
    fun galleryScope_serializationRoundTrip() {
        assertEquals(GalleryScope.Library, GalleryScope.parse(GalleryScope.Library.serialize()))
        val folder = GalleryScope.Folder(VfsPath.Local("/storage/emulated/0/DCIM"), includeSubtree = true, hiddenByNomedia = false)
        assertEquals(folder, GalleryScope.parse(folder.serialize()))
        val safFolder = GalleryScope.Folder(
            VfsPath.Saf("content://tree/1", "1A2B-3C4D:DCIM/100CANON"),
            includeSubtree = false,
            hiddenByNomedia = true,
        )
        assertEquals(safFolder, GalleryScope.parse(safFolder.serialize()))
    }

    @Test
    fun hasAnyMedia_shortCircuitsOnFirstMediaAndRespectsNomedia() = runBlocking {
        val fakeFs = FakeFileSystem()
        val dcim = fakeFs.createDirectory(fakeFs.rootPath(), "DCIM")
        val emptyDir = fakeFs.createDirectory(dcim, "Empty")
        val nomediaDir = fakeFs.createDirectory(dcim, "Private")
        fakeFs.createFile(nomediaDir, ".nomedia", "application/octet-stream")
        fakeFs.createFile(nomediaDir, "hidden.jpg", "image/jpeg")

        val vfs = VfsListingIndexSource(fakeFs)
        assertFalse(vfs.hasAnyMedia(emptyDir, includeSubtree = true))
        // DCIM only has a .nomedia subfolder so subtree probe ignores it
        assertFalse(vfs.hasAnyMedia(dcim, includeSubtree = true))

        val cam = fakeFs.createDirectory(dcim, "Camera")
        fakeFs.createFile(cam, "photo.jpg", "image/jpeg")
        assertTrue(vfs.hasAnyMedia(dcim, includeSubtree = true))
    }

    @Test
    fun reconcile_deduplicatesRepeatedRescanForUnchangedPathAndMtime() = runBlocking {
        val dcim = tempFolder.newFolder("DCIM_dedup")
        val unindexed = File(dcim, "fresh.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val unindexedPath = VfsPath.Local(unindexed.absolutePath)

        val rescanned = mutableListOf<VfsPath>()
        val notifier = object : MediaScanNotifier {
            override fun rescan(path: VfsPath) { rescanned.add(path) }
            override fun rescanFiles(paths: List<VfsPath>) { rescanned.addAll(paths) }
        }

        val repo = GalleryRepository(
            fs = FileSystemRepository(listOf(LocalFileSystem())),
            primaryIndexSource = FakeIndexSource(emptyList()),
            mediaScanNotifier = notifier,
        )
        val scope = GalleryScope.Folder(VfsPath.Local(dcim.absolutePath), includeSubtree = true)

        repo.loadAndReconcile(scope)
        assertEquals(listOf(unindexedPath), rescanned)

        // Second reconciliation with unchanged mtime must not loop rescanning the same file
        rescanned.clear()
        repo.loadAndReconcile(scope)
        assertTrue("Duplicate rescan should be suppressed", rescanned.isEmpty())
    }

    @Test
    fun optimisticUpsert_onlyInsertsItemsInsideCurrentScopeWhileStillRescanning() = runBlocking {
        val fakeFs = FakeFileSystem()
        val dcim = fakeFs.createDirectory(fakeFs.rootPath(), "DCIM")
        val cam = fakeFs.createDirectory(dcim, "Camera")
        val other = fakeFs.createDirectory(fakeFs.rootPath(), "Downloads")
        val insideImg = fakeFs.createFile(cam, "a.jpg", "image/jpeg")
        val outsideImg = fakeFs.createFile(other, "b_edited.jpg", "image/jpeg")

        val rescanned = mutableListOf<VfsPath>()
        val notifier = object : MediaScanNotifier {
            override fun rescan(path: VfsPath) { rescanned.add(path) }
            override fun rescanFiles(paths: List<VfsPath>) { rescanned.addAll(paths) }
        }

        val repo = GalleryRepository(fs = fakeFs, mediaScanNotifier = notifier)
        repo.loadAndReconcile(GalleryScope.Folder(cam, includeSubtree = true))

        val outsideItem = MediaItem(
            path = outsideImg,
            name = "b_edited.jpg",
            kind = MediaKind.IMAGE,
            mime = "image/jpeg",
            sizeBytes = 10L,
            bestDateMs = 1_700_000_000_000L,
            modifiedMs = 1_700_000_000_000L,
        )
        val after = repo.applyOptimisticMutation(GalleryMutation.Upserted(listOf(outsideItem)))
        assertEquals(listOf(insideImg), after!!.items.map { it.path })
        assertEquals(listOf(outsideImg), rescanned)
    }
}
