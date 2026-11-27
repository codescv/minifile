package com.minifile.explorer.core.gallery

import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson

enum class CropAspectRatio(val ratio: Float?) {
    FREE(null),
    ORIGINAL(null),
    SQUARE_1_1(1f),
    RATIO_4_3(4f / 3f),
    RATIO_3_4(3f / 4f),
    RATIO_16_9(16f / 9f),
    RATIO_9_16(9f / 16f),
}

enum class CropCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun isFullImage(eps: Float = 1e-4f): Boolean =
        left <= eps && top <= eps && right >= 1f - eps && bottom >= 1f - eps

    fun clamped(minExtent: Float = 0.01f): NormalizedRect {
        val l = left.coerceIn(0f, 1f - minExtent)
        val t = top.coerceIn(0f, 1f - minExtent)
        val r = right.coerceIn(l + minExtent, 1f)
        val b = bottom.coerceIn(t + minExtent, 1f)
        return NormalizedRect(l, t, r, b)
    }

    fun toJson(): JsonValue.Arr = JsonValue.Arr(
        listOf(
            JsonValue.Num(left.toDouble()),
            JsonValue.Num(top.toDouble()),
            JsonValue.Num(right.toDouble()),
            JsonValue.Num(bottom.toDouble()),
        ),
    )

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)

        fun fromJson(arr: JsonValue.Arr): NormalizedRect? {
            if (arr.items.size != 4) return null
            val nums = arr.items.map { (it as? JsonValue.Num)?.toDoubleOrNull()?.toFloat() ?: return null }
            return NormalizedRect(nums[0], nums[1], nums[2], nums[3]).clamped()
        }
    }
}

data class VideoEditSpec(
    val keepRangesMs: List<LongRange>,
    val mute: Boolean = false,
) {
    fun isValid(totalDurationMs: Long): Boolean {
        if (keepRangesMs.isEmpty() || keepRangesMs.size > MAX_KEEP_SEGMENTS) return false
        var prevEnd = -1L
        for (r in keepRangesMs) {
            if (r.first < 0L) return false
            if (totalDurationMs > 0L && r.last > totalDurationMs) return false
            if (r.last - r.first < MIN_SEGMENT_DURATION_MS) return false
            if (prevEnd >= 0L && r.first <= prevEnd) return false
            prevEnd = r.last
        }
        return true
    }

    fun isIdentity(totalDurationMs: Long): Boolean {
        if (mute) return false
        if (keepRangesMs.size != 1) return false
        val r = keepRangesMs[0]
        return r.first <= 0L && (totalDurationMs <= 0L || r.last >= totalDurationMs)
    }

    fun toJson(): JsonValue.Obj = MiniJson.obj(
        "keep" to JsonValue.Arr(
            keepRangesMs.map { r ->
                JsonValue.Arr(listOf(JsonValue.Num(r.first), JsonValue.Num(r.last)))
            },
        ),
        "mute" to JsonValue.Bool(mute),
    )

    companion object {
        const val MAX_KEEP_SEGMENTS = 2
        const val MIN_SEGMENT_DURATION_MS = 500L

        fun fromJson(obj: JsonValue.Obj): VideoEditSpec? {
            val keepArr = obj.entries["keep"] as? JsonValue.Arr ?: return null
            val ranges = keepArr.items.mapNotNull { item ->
                val pair = item as? JsonValue.Arr ?: return@mapNotNull null
                if (pair.items.size != 2) return@mapNotNull null
                val start = (pair.items[0] as? JsonValue.Num)?.toLongOrNull() ?: return@mapNotNull null
                val end = (pair.items[1] as? JsonValue.Num)?.toLongOrNull() ?: return@mapNotNull null
                start..end
            }
            if (ranges.isEmpty() || ranges.size > MAX_KEEP_SEGMENTS) return null
            return VideoEditSpec(
                keepRangesMs = ranges,
                mute = obj.bool("mute", false),
            )
        }
    }
}

/**
 * Cross-platform declarative EditRecipe matching spec §7.1.
 */
data class EditRecipe(
    val version: Int = 1,
    val kind: MediaKind,
    val rotateDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val crop: NormalizedRect? = null,
    val video: VideoEditSpec? = null,
) {
    val normalizedRotateDegrees: Int
        get() = ((rotateDegrees % 360) + 360) % 360

    fun isIdentity(sourceDurationMs: Long = 0L): Boolean {
        val noSpatial = normalizedRotateDegrees == 0 && !flipHorizontal && (crop == null || crop.isFullImage())
        return when (kind) {
            MediaKind.IMAGE -> noSpatial
            MediaKind.VIDEO -> noSpatial && (video == null || video.isIdentity(sourceDurationMs))
        }
    }

    fun toJson(): JsonValue.Obj {
        val pairs = mutableListOf<Pair<String, JsonValue>>(
            "v" to JsonValue.Num(version),
            "kind" to JsonValue.Str(if (kind == MediaKind.IMAGE) "image" else "video"),
            "rotate" to JsonValue.Num(normalizedRotateDegrees),
            "flipH" to JsonValue.Bool(flipHorizontal),
            "crop" to (crop?.toJson() ?: JsonValue.Null),
        )
        if (video != null) {
            pairs.add("video" to video.toJson())
        }
        return MiniJson.obj(*pairs.toTypedArray())
    }

    fun toJsonString(): String = MiniJson.stringify(toJson())

    companion object {
        fun fromJsonString(raw: String): EditRecipe? {
            val obj = runCatching { MiniJson.parse(raw) as? JsonValue.Obj }.getOrNull() ?: return null
            val v = obj.int("v", 1)
            val kind = when (obj.str("kind")) {
                "image" -> MediaKind.IMAGE
                "video" -> MediaKind.VIDEO
                else -> return null
            }
            val rotate = ((obj.int("rotate", 0) % 360) + 360) % 360
            if (rotate !in setOf(0, 90, 180, 270)) return null
            val flipH = obj.bool("flipH", false)
            val crop = (obj.entries["crop"] as? JsonValue.Arr)?.let(NormalizedRect::fromJson)
            val video = (obj.entries["video"] as? JsonValue.Obj)?.let(VideoEditSpec::fromJson)
            return EditRecipe(
                version = v,
                kind = kind,
                rotateDegrees = rotate,
                flipHorizontal = flipH,
                crop = crop,
                video = video,
            )
        }
    }
}
