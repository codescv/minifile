package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.model.MediaScanNotifier
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

/**
 * Adversarial regression tests authored by android-tester (review round 1).
 * Each test pins a concrete bug found during review; they must pass once the fixes land.
 */
class GalleryAdversarialReviewTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeIndexSource(private val items: List<MediaItem>) : MediaIndexSource {
        override suspend fun fetchFastHead(scope: GalleryScope, limit: Int): List<MediaItem> = items.take(limit)
        override suspend fun fetchAll(scope: GalleryScope, includeHidden: Boolean): List<MediaItem> = items
        override suspend fun fetchRecent(limit: Int): List<MediaItem> = items.take(limit)
    }

    private class RecordingNotifier : MediaScanNotifier {
        val rescanned = mutableListOf<VfsPath>()
        override fun rescan(path: VfsPath) {
            rescanned.add(path)
        }

        override fun rescanFiles(paths: List<VfsPath>) {
            rescanned.addAll(paths)
        }
    }

    /**
     * R1-1: VfsListingIndexSource silently stops at maxDepth=3 WITHOUT setting truncatedByBudget,
     * so GalleryRepository treats MediaStore rows deeper than 3 levels as "ghosts": they vanish from
     * the Pictures/DCIM root timeline and are re-sent to rescanFiles on every load.
     */
    @Test
    fun deepIndexedFileBeyondScanDepth_isNeitherDroppedNorTreatedAsGhost() = runBlocking {
        val pictures = tempFolder.newFolder("Pictures")
        val deepDir = File(pictures, "A/B/C/D").apply { mkdirs() }
        val deepFile = File(deepDir, "deep.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val deepPath = VfsPath.Local(deepFile.absolutePath)
        assertTrue(deepFile.isFile)

        val notifier = RecordingNotifier()
        val repo = GalleryRepository(
            fs = FileSystemRepository(listOf(LocalFileSystem())),
            primaryIndexSource = FakeIndexSource(
                listOf(
                    MediaItem(
                        path = deepPath,
                        name = "deep.jpg",
                        kind = MediaKind.IMAGE,
                        mime = "image/jpeg",
                        sizeBytes = 3L,
                        bestDateMs = 1_700_000_000_000L,
                        modifiedMs = 1_700_000_000_000L,
                        storeId = 7L,
                    ),
                ),
            ),
            mediaScanNotifier = notifier,
        )

        val snap = repo.loadAndReconcile(GalleryScope.Folder(VfsPath.Local(pictures.absolutePath), includeSubtree = true))

        assertTrue("indexed file that exists on disk must stay visible", snap.items.any { it.path == deepPath })
        assertEquals("existing file must not be counted as a ghost row", 0, snap.reconciledGhostCount)
        assertFalse("existing indexed file must not be re-scanned on every load", deepPath in notifier.rescanned)
    }

    /**
     * R1-2: an inherited (ancestor) GALLERY override bypasses the hidden-dot-segment rule,
     * so DCIM/.thumbnails opens as a gallery once the user toggled "View as gallery" on DCIM.
     */
    @Test
    fun inheritedGalleryOverride_doesNotActivateInsideHiddenDotSegment() {
        val dcim = VfsPath.Local("/storage/emulated/0/DCIM")
        val thumbs = VfsPath.Local("/storage/emulated/0/DCIM/.thumbnails")
        val prefs = FolderModePreferences().record(dcim, FolderLensOverride.GALLERY, nowMs = 1L)

        val decision = GalleryScopePolicy.decide(
            dir = thumbs,
            volumeRoots = listOf(VfsPath.Local("/storage/emulated/0")),
            overrides = prefs,
            mediaCountInSubtree = 5,
        )
        assertEquals(BrowseLens.FILES, decision.lens)
    }

    /**
     * R1-3: a GALLERY override recorded on a broad ancestor (e.g. the volume root via the Files-lens
     * "View as gallery" menu) is inherited by every descendant, even folders with zero media
     * (Download, Android, Documents...), producing empty galleries and subtree scans everywhere.
     * Inherited GALLERY overrides must still require media in the subtree.
     */
    @Test
    fun inheritedGalleryOverride_requiresMediaInSubtree() {
        val root = VfsPath.Local("/storage/emulated/0")
        val docs = VfsPath.Local("/storage/emulated/0/Documents")
        val prefs = FolderModePreferences().record(root, FolderLensOverride.GALLERY, nowMs = 1L)

        val decision = GalleryScopePolicy.decide(
            dir = docs,
            volumeRoots = listOf(root),
            overrides = prefs,
            mediaCountInSubtree = 0,
        )
        assertEquals(BrowseLens.FILES, decision.lens)
    }
}
