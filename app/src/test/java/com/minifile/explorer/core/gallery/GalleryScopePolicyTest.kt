package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.vfs.VfsPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryScopePolicyTest {

    private val primaryRoot = VfsPath.Local("/storage/emulated/0")
    private val sdRoot = VfsPath.Local("/storage/1A2B-3C4D")
    private val volumeRoots = listOf(primaryRoot, sdRoot)

    @Test
    fun localDcimAndPictures_autoActivateAndRespectStrictSegmentBoundaries() {
        val dcim = VfsPath.Local("/storage/emulated/0/DCIM")
        val camera = VfsPath.Local("/storage/emulated/0/DCIM/Camera")
        val picturesLower = VfsPath.Local("/storage/emulated/0/pictures/Screenshots")
        val sdCamera = VfsPath.Local("/storage/1A2B-3C4D/DCIM/100CANON")

        val dcimDec = GalleryScopePolicy.decide(dcim, volumeRoots, mediaCountInSubtree = 5)
        assertEquals(BrowseLens.GALLERY, dcimDec.lens)
        assertTrue(dcimDec.isMediaRootAggregate)
        assertTrue(dcimDec.isAutoActivated)

        val camDec = GalleryScopePolicy.decide(camera, volumeRoots, mediaCountInSubtree = 3)
        assertEquals(BrowseLens.GALLERY, camDec.lens)
        assertFalse(camDec.isMediaRootAggregate)
        assertTrue(camDec.isAutoActivated)

        val picDec = GalleryScopePolicy.decide(picturesLower, volumeRoots, mediaCountInSubtree = 2)
        assertEquals(BrowseLens.GALLERY, picDec.lens)

        val sdDec = GalleryScopePolicy.decide(sdCamera, volumeRoots, mediaCountInSubtree = 10)
        assertEquals(BrowseLens.GALLERY, sdDec.lens)

        // Strict segment boundaries: DCIM_backup, Pictures2, Download must NOT trigger auto gallery
        for (nonMedia in listOf(
            VfsPath.Local("/storage/emulated/0/DCIM_backup"),
            VfsPath.Local("/storage/emulated/0/DCIM_backup/Camera"),
            VfsPath.Local("/storage/emulated/0/Pictures2"),
            VfsPath.Local("/storage/emulated/0/Download"),
            VfsPath.Local("/storage/emulated/0/Documents/DCIM"),
        )) {
            val d = GalleryScopePolicy.decide(nonMedia, volumeRoots, mediaCountInSubtree = 10)
            assertEquals("Expected FILES for $nonMedia", BrowseLens.FILES, d.lens)
            assertNull(d.scope)
        }
    }

    @Test
    fun safDocumentId_autoActivatesForCameraSdCardAndRejectsNonMediaRoots() {
        val tree = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A"
        val safCanon = VfsPath.Saf(tree, "1A2B-3C4D:DCIM/100CANON")
        val safRootDcim = VfsPath.Saf(tree, "primary:DCIM")
        val safBackup = VfsPath.Saf(tree, "1A2B-3C4D:DCIM_backup/100CANON")

        val dCanon = GalleryScopePolicy.decide(safCanon, mediaCountInSubtree = 20)
        assertEquals(BrowseLens.GALLERY, dCanon.lens)
        assertFalse(dCanon.isMediaRootAggregate)

        val dRoot = GalleryScopePolicy.decide(safRootDcim, mediaCountInSubtree = 20)
        assertEquals(BrowseLens.GALLERY, dRoot.lens)
        assertTrue(dRoot.isMediaRootAggregate)

        val dBackup = GalleryScopePolicy.decide(safBackup, mediaCountInSubtree = 20)
        assertEquals(BrowseLens.FILES, dBackup.lens)
    }

    @Test
    fun hiddenDotSegmentsAndPendingTrashedNames_areExcluded() {
        for (dotPath in listOf(
            VfsPath.Local("/storage/emulated/0/DCIM/.thumbnails"),
            VfsPath.Local("/storage/emulated/0/DCIM/.trashed-1700000"),
            VfsPath.Local("/storage/emulated/0/Pictures/.pending-999"),
            VfsPath.Local("/storage/emulated/0/DCIM/Camera/.cache"),
        )) {
            assertTrue(GalleryScopePolicy.hasHiddenDotSegmentUnderMediaRoot(dotPath, volumeRoots))
            val dec = GalleryScopePolicy.decide(dotPath, volumeRoots, mediaCountInSubtree = 10)
            assertEquals("Dot segment $dotPath must stay FILES", BrowseLens.FILES, dec.lens)
        }

        assertTrue(GalleryScopePolicy.isPendingOrTrashedName(".pending-1700000-IMG_01.jpg"))
        assertTrue(GalleryScopePolicy.isPendingOrTrashedName(".trashed-1700000-VID_02.mp4"))
        assertFalse(GalleryScopePolicy.isPendingOrTrashedName("IMG_01.jpg"))
    }

    @Test
    fun nomediaEmptyDirAndGlobalSwitch_followSpec() {
        val secretDir = VfsPath.Local("/storage/emulated/0/Pictures/Private")

        val nomediaDec = GalleryScopePolicy.decide(
            dir = secretDir,
            volumeRoots = volumeRoots,
            hasNomediaInDirOrAncestorToMediaRoot = true,
            mediaCountInSubtree = 4,
        )
        assertEquals(BrowseLens.GALLERY, nomediaDec.lens)
        assertTrue(nomediaDec.hiddenByNomedia)
        assertFalse((nomediaDec.scope as GalleryScope.Folder).includeSubtree)

        val emptyDec = GalleryScopePolicy.decide(
            dir = secretDir,
            volumeRoots = volumeRoots,
            mediaCountInSubtree = 0,
        )
        assertEquals(BrowseLens.FILES, emptyDec.lens)

        val switchOffDec = GalleryScopePolicy.decide(
            dir = secretDir,
            volumeRoots = volumeRoots,
            autoOpenMediaFolders = false,
            mediaCountInSubtree = 10,
        )
        assertEquals(BrowseLens.FILES, switchOffDec.lens)
    }

    @Test
    fun ftpAndGdrive_neverAutoActivateButHonorManualGalleryOverride() {
        val ftpDcim = VfsPath.Ftp("nas1", "/DCIM")
        val gdPic = VfsPath.GDrive("gd1", "/Pictures")

        assertEquals(BrowseLens.FILES, GalleryScopePolicy.decide(ftpDcim, mediaCountInSubtree = 50).lens)
        assertEquals(BrowseLens.FILES, GalleryScopePolicy.decide(gdPic, mediaCountInSubtree = 50).lens)

        val overrides = FolderModePreferences().record(ftpDcim, FolderLensOverride.GALLERY, nowMs = 100L)
        assertEquals(BrowseLens.GALLERY, GalleryScopePolicy.decide(ftpDcim, overrides = overrides, mediaCountInSubtree = 50).lens)
    }

    @Test
    fun nearestAncestorOverrideInheritanceLru64AndJsonRoundTrip() {
        val dcim = VfsPath.Local("/storage/emulated/0/DCIM")
        val camera = VfsPath.Local("/storage/emulated/0/DCIM/Camera")
        val deep = VfsPath.Local("/storage/emulated/0/DCIM/Camera/Trip")

        var prefs = FolderModePreferences(maxEntries = 64)
            .record(dcim, FolderLensOverride.FILES, nowMs = 10L, alwaysFiles = true)

        // Child inherits nearest ancestor FILES override
        assertEquals(
            BrowseLens.FILES,
            GalleryScopePolicy.decide(camera, volumeRoots, overrides = prefs, mediaCountInSubtree = 5).lens,
        )

        // Deeper child explicitly set back to GALLERY overrides parent
        prefs = prefs.record(deep, FolderLensOverride.GALLERY, nowMs = 20L)
        assertEquals(
            BrowseLens.GALLERY,
            GalleryScopePolicy.decide(deep, volumeRoots, overrides = prefs, mediaCountInSubtree = 5).lens,
        )

        // JSON persistence round-trip
        val restored = FolderModePreferences.fromJsonString(prefs.toJsonString(), maxEntries = 64)
        assertEquals(2, restored.entries.size)
        val resolvedDcim = restored.resolveNearestAncestor(dcim)
        assertNotNull(resolvedDcim)
        assertTrue(resolvedDcim!!.second.alwaysFiles)

        // LRU 64 eviction: inserting 70 entries evicts the 6 oldest
        var lru = FolderModePreferences(maxEntries = 64)
        for (i in 1..70) {
            lru = lru.record(VfsPath.Local("/storage/emulated/0/Folder$i"), FolderLensOverride.FILES, nowMs = i.toLong())
        }
        assertEquals(64, lru.entries.size)
        assertNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/Folder1")))
        assertNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/Folder6")))
        assertNotNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/Folder7")))
        assertNotNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/Folder70")))
    }

    @Test
    fun touchRefreshesLruRecencySoHotEntrySurvivesEviction() {
        var lru = FolderModePreferences(maxEntries = 3)
            .record(VfsPath.Local("/storage/emulated/0/A"), FolderLensOverride.FILES, nowMs = 1L)
            .record(VfsPath.Local("/storage/emulated/0/B"), FolderLensOverride.FILES, nowMs = 2L)
            .record(VfsPath.Local("/storage/emulated/0/C"), FolderLensOverride.FILES, nowMs = 3L)

        // Reading/touching A bumps its recency above B & C
        lru = lru.touch(VfsPath.Local("/storage/emulated/0/A"), nowMs = 10L)
        lru = lru.record(VfsPath.Local("/storage/emulated/0/D"), FolderLensOverride.FILES, nowMs = 11L)

        assertNotNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/A")))
        assertNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/B")))
        assertNotNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/C")))
        assertNotNull(lru.resolveNearestAncestor(VfsPath.Local("/storage/emulated/0/D")))
    }
}
