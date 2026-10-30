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
                add(File(dir, ".nomedia").absolutePath)
                dir.parentFile?.let { add(it.absolutePath) }
            }.toTypedArray()
            runCatching {
                MediaScannerConnection.scanFile(appContext, targets, null, null)
            }
        }
    }
}
