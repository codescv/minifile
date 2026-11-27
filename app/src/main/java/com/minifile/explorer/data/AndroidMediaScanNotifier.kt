package com.minifile.explorer.data

import android.content.Context
import android.media.MediaScannerConnection
import com.minifile.explorer.core.model.MediaScanNotifier
import com.minifile.explorer.core.vfs.VfsPath
import java.io.File

class AndroidMediaScanNotifier(
    context: Context,
) : MediaScanNotifier {
    private val appContext = context.applicationContext

    override fun rescan(path: VfsPath) {
        if (path is VfsPath.Local) {
            val dir = File(path.normalized)
            val targets = buildList {
                add(dir.absolutePath)
                if (dir.isDirectory) {
                    add(File(dir, ".nomedia").absolutePath)
                }
                dir.parentFile?.let { add(it.absolutePath) }
            }.distinct().toTypedArray()
            runCatching {
                MediaScannerConnection.scanFile(appContext, targets, null, null)
            }
        }
    }

    override fun rescanFiles(paths: List<VfsPath>) {
        val targets = linkedSetOf<String>()
        for (path in paths) {
            if (path is VfsPath.Local) {
                val f = File(path.normalized)
                targets.add(f.absolutePath)
            }
        }
        if (targets.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(appContext, targets.toTypedArray(), null, null)
        }
    }
}
