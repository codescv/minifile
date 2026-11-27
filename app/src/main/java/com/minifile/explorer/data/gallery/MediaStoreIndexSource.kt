package com.minifile.explorer.data.gallery

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import com.minifile.explorer.core.gallery.GalleryScope
import com.minifile.explorer.core.gallery.GalleryScopePolicy
import com.minifile.explorer.core.gallery.MediaExtensionRegistry
import com.minifile.explorer.core.gallery.MediaIndexSource
import com.minifile.explorer.core.gallery.MediaItem
import com.minifile.explorer.core.gallery.MediaKind
import com.minifile.explorer.core.gallery.TimelineBuilder
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android MediaStore-backed [MediaIndexSource].
 *
 * Implements two-stage querying:
 * - Stage 0 (`fetchFastHead` / `fetchRecent`): uses `QUERY_ARG_LIMIT` on API 30+,
 *   falling back to `sortOrder + " LIMIT N"` on API 26-29.
 * - API 30+ enforces `IS_PENDING = 0 AND IS_TRASHED = 0`; all APIs also filter
 *   `.pending-*` and `.trashed-*` names defensively.
 * - Maintains `filesDir/gallery/head.json` (<= 8 recent items) for instant Home strip warm-up.
 */
class MediaStoreIndexSource(
    context: Context,
) : MediaIndexSource {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val headCacheFile = File(File(appContext.filesDir, "gallery"), "head.json")

    override suspend fun fetchFastHead(scope: GalleryScope, limit: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            queryMediaStore(scope = scope, limit = limit.coerceAtLeast(1), includeHidden = false)
        }

    override suspend fun fetchAll(scope: GalleryScope, includeHidden: Boolean): List<MediaItem> =
        withContext(Dispatchers.IO) {
            queryMediaStore(scope = scope, limit = null, includeHidden = includeHidden)
        }

    override suspend fun fetchRecent(limit: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = queryMediaStore(scope = GalleryScope.Library, limit = limit.coerceAtLeast(1), includeHidden = false)
            if (items.isNotEmpty()) {
                writeHeadCache(items.take(MAX_HEAD_CACHE_ITEMS))
            }
            items
        }

    fun readHeadCache(): List<MediaItem> {
        if (!headCacheFile.isFile) return emptyList()
        return runCatching {
            val raw = headCacheFile.readText(Charsets.UTF_8)
            val arr = MiniJson.parse(raw) as? JsonValue.Arr ?: return@runCatching emptyList()
            arr.items.mapNotNull { el ->
                val obj = el as? JsonValue.Obj ?: return@mapNotNull null
                val pathRaw = obj.str("path") ?: return@mapNotNull null
                val path = runCatching { VfsPath.parse(pathRaw) }.getOrNull() ?: return@mapNotNull null
                val name = obj.str("name") ?: path.displayName
                val kind = if (obj.str("kind") == "VIDEO") MediaKind.VIDEO else MediaKind.IMAGE
                MediaItem(
                    path = path,
                    name = name,
                    kind = kind,
                    mime = obj.str("mime") ?: if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg",
                    sizeBytes = obj.long("size", 0L),
                    bestDateMs = obj.long("bestDateMs", 0L),
                    modifiedMs = obj.long("modifiedMs", 0L),
                    width = obj.int("w", 0),
                    height = obj.int("h", 0),
                    orientation = obj.int("rot", 0),
                    durationMs = obj.long("dur", 0L),
                    bucketDir = obj.str("bucket") ?: "",
                    storeId = obj.long("storeId", -1L),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun writeHeadCache(items: List<MediaItem>) {
        runCatching {
            val parent = headCacheFile.parentFile ?: return
            parent.mkdirs()
            val arr = JsonValue.Arr(
                items.take(MAX_HEAD_CACHE_ITEMS).map { item ->
                    MiniJson.obj(
                        "path" to JsonValue.Str(item.path.serialize()),
                        "name" to JsonValue.Str(item.name),
                        "kind" to JsonValue.Str(item.kind.name),
                        "mime" to JsonValue.Str(item.mime),
                        "size" to JsonValue.Num(item.sizeBytes),
                        "bestDateMs" to JsonValue.Num(item.bestDateMs),
                        "modifiedMs" to JsonValue.Num(item.modifiedMs),
                        "w" to JsonValue.Num(item.width),
                        "h" to JsonValue.Num(item.height),
                        "rot" to JsonValue.Num(item.orientation),
                        "dur" to JsonValue.Num(item.durationMs),
                        "bucket" to JsonValue.Str(item.bucketDir),
                        "storeId" to JsonValue.Num(item.storeId),
                    )
                },
            )
            val tmp = File(parent, "head.json.tmp")
            tmp.writeText(MiniJson.stringify(arr), Charsets.UTF_8)
            if (!tmp.renameTo(headCacheFile)) {
                tmp.copyTo(headCacheFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun queryMediaStore(
        scope: GalleryScope,
        limit: Int?,
        includeHidden: Boolean,
    ): List<MediaItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DATA)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            add(MediaStore.Files.FileColumns.WIDTH)
            add(MediaStore.Files.FileColumns.HEIGHT)
            add(MediaStore.Images.Media.ORIENTATION)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        }.toTypedArray()

        val whereParts = ArrayList<String>()
        val args = ArrayList<String>()
        whereParts.add(
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)",
        )
        args.add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
        args.add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            whereParts.add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            whereParts.add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
        }

        if (scope is GalleryScope.Folder) {
            val local = scope.dir as? VfsPath.Local ?: return emptyList()
            val dirPath = local.normalized.trimEnd('/')
            val primaryRoot = Environment.getExternalStorageDirectory()?.absolutePath?.trimEnd('/')
            val useRelPath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                primaryRoot != null &&
                (dirPath == primaryRoot || dirPath.startsWith("$primaryRoot/"))

            if (useRelPath && primaryRoot != null) {
                val rel = dirPath.removePrefix(primaryRoot).trim('/')
                val relPrefix = if (rel.isEmpty()) "" else "$rel/"
                if (scope.includeSubtree) {
                    whereParts.add("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'")
                    args.add("${escapeSqlLike(relPrefix)}%")
                } else {
                    whereParts.add("${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
                    args.add(relPrefix)
                }
            } else {
                whereParts.add("${MediaStore.Files.FileColumns.DATA} LIKE ? ESCAPE '\\'")
                args.add("${escapeSqlLike("$dirPath/")}%")
            }
        }

        val selection = whereParts.joinToString(" AND ")
        val selectionArgs = args.toTypedArray()
        val sortSql = "COALESCE(NULLIF(${MediaStore.Images.Media.DATE_TAKEN}, 0), ${MediaStore.Files.FileColumns.DATE_MODIFIED} * 1000) DESC, ${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val cursor: Cursor = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val queryArgs = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortSql)
                    if (limit != null) {
                        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                    }
                }
                resolver.query(uri, projection, queryArgs, null)
            } else {
                val order = if (limit != null) "$sortSql LIMIT $limit" else sortSql
                resolver.query(uri, projection, selection, selectionArgs, order)
            }
        }.getOrNull() ?: return emptyList()

        val scopeLocalDir = (scope as? GalleryScope.Folder)?.dir as? VfsPath.Local
        val scopeSubtree = (scope as? GalleryScope.Folder)?.includeSubtree ?: true

        val out = ArrayList<MediaItem>(limit ?: 256)
        cursor.use { c ->
            val idxId = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val idxData = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val idxName = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val idxMime = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val idxSize = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val idxType = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val idxTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val idxMod = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val idxW = c.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
            val idxH = c.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
            val idxOrient = c.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            val idxDur = c.getColumnIndex(MediaStore.Video.Media.DURATION)
            val idxBucket = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (c.moveToNext()) {
                val data = if (idxData >= 0 && !c.isNull(idxData)) c.getString(idxData) else null
                if (data.isNullOrBlank()) continue
                val name = (if (idxName >= 0 && !c.isNull(idxName)) c.getString(idxName) else null)
                    ?: data.substringAfterLast('/')
                if (GalleryScopePolicy.isPendingOrTrashedName(name)) continue
                if (!includeHidden && name.startsWith(".")) continue

                val localPath = VfsPath.Local(data)
                if (scopeLocalDir != null && !scopeSubtree) {
                    val parentNorm = localPath.normalized.substringBeforeLast('/', "")
                    if (parentNorm != scopeLocalDir.normalized) continue
                }

                val mediaType = if (idxType >= 0 && !c.isNull(idxType)) c.getInt(idxType) else -1
                val mime = if (idxMime >= 0 && !c.isNull(idxMime)) c.getString(idxMime).orEmpty() else ""
                val kind = when (mediaType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaKind.IMAGE
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaKind.VIDEO
                    else -> MediaExtensionRegistry.classify(name, mime) ?: continue
                }
                val id = if (idxId >= 0 && !c.isNull(idxId)) c.getLong(idxId) else -1L
                val size = if (idxSize >= 0 && !c.isNull(idxSize)) c.getLong(idxSize).coerceAtLeast(0L) else 0L
                val dateTaken = if (idxTaken >= 0 && !c.isNull(idxTaken)) c.getLong(idxTaken) else null
                val dateModSec = if (idxMod >= 0 && !c.isNull(idxMod)) c.getLong(idxMod) else null
                val modMs = (dateModSec ?: 0L) * 1000L
                val bestDate = TimelineBuilder.bestDateMs(dateTaken, dateModSec, modMs)
                val w = if (idxW >= 0 && !c.isNull(idxW)) c.getInt(idxW).coerceAtLeast(0) else 0
                val h = if (idxH >= 0 && !c.isNull(idxH)) c.getInt(idxH).coerceAtLeast(0) else 0
                val orient = if (idxOrient >= 0 && !c.isNull(idxOrient)) c.getInt(idxOrient) else 0
                val dur = if (idxDur >= 0 && !c.isNull(idxDur)) c.getLong(idxDur).coerceAtLeast(0L) else 0L
                val bucket = if (idxBucket >= 0 && !c.isNull(idxBucket)) c.getString(idxBucket).orEmpty() else ""

                out.add(
                    MediaItem(
                        path = localPath,
                        name = name,
                        kind = kind,
                        mime = mime.ifBlank { if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg" },
                        sizeBytes = size,
                        bestDateMs = bestDate,
                        modifiedMs = modMs,
                        width = w,
                        height = h,
                        orientation = orient,
                        durationMs = dur,
                        bucketDir = bucket.ifBlank { data.substringBeforeLast('/').substringAfterLast('/') },
                        storeId = id,
                    ),
                )
            }
        }
        return out
    }

    companion object {
        private const val MAX_HEAD_CACHE_ITEMS = 8

        internal fun escapeSqlLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
