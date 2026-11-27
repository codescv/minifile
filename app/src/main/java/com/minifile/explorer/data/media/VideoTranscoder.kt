package com.minifile.explorer.data.media

import com.minifile.explorer.core.gallery.EditRecipe
import java.io.File

/**
 * Reserved extension point for future spatial video crop / resolution downscale transcoding
 * (Later milestone; candidate backend: media3-transformer).
 *
 * P2 uses [LosslessMp4Remuxer] for zero-reencode trim / single-cut / mute / rotate.
 */
interface VideoTranscoder {
    fun isAvailable(): Boolean
    suspend fun transcode(
        source: File,
        destination: File,
        recipe: EditRecipe,
        onProgress: (Float) -> Unit = {},
    ): Result<File>

    companion object {
        val UNAVAILABLE: VideoTranscoder = object : VideoTranscoder {
            override fun isAvailable(): Boolean = false
            override suspend fun transcode(
                source: File,
                destination: File,
                recipe: EditRecipe,
                onProgress: (Float) -> Unit,
            ): Result<File> = Result.failure(UnsupportedOperationException("Video transcoding is not enabled in this build"))
        }
    }
}
