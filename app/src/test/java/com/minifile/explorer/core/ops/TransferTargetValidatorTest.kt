package com.minifile.explorer.core.ops

import com.minifile.explorer.core.model.TransferMode
import com.minifile.explorer.core.vfs.FakeFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTargetValidatorTest {

    @Test
    fun validatesSameDirectoryInsideSourceReadOnlyAndUnavailable() = runBlocking {
        val fs = FakeFileSystem("content://saf/fav")
        val root = fs.rootPath()
        val downloads = fs.createDirectory(root, "Download")
        val sub = fs.createDirectory(downloads, "Telegram")
        val backup = fs.createDirectory(root, "Backup")
        val file = fs.createFile(downloads, "doc.pdf")

        // Valid destination
        assertEquals(
            TargetValidation.Allowed,
            TransferTargetValidator.validate(fs, listOf(file), TransferMode.MOVE, backup),
        )

        // Same directory
        assertEquals(
            TargetValidation.SameDirectory,
            TransferTargetValidator.validate(fs, listOf(file), TransferMode.MOVE, downloads),
        )

        // Inside source (moving folder into itself or child)
        assertEquals(
            TargetValidation.InsideSource,
            TransferTargetValidator.validate(fs, listOf(downloads), TransferMode.MOVE, downloads),
        )
        assertEquals(
            TargetValidation.InsideSource,
            TransferTargetValidator.validate(fs, listOf(downloads), TransferMode.COPY, sub),
        )

        // Read-only target
        fs.readOnly = true
        assertEquals(
            TargetValidation.ReadOnlyTarget,
            TransferTargetValidator.validate(fs, listOf(file), TransferMode.COPY, backup),
        )

        // Unavailable target
        fs.readOnly = false
        fs.available = false
        val unavail = TransferTargetValidator.validate(fs, listOf(file), TransferMode.COPY, backup)
        assertTrue(unavail is TargetValidation.TargetUnavailable)
    }
}
