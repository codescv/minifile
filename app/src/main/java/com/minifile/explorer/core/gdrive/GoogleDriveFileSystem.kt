package com.minifile.explorer.core.gdrive

import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.Logger
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.FileSystem
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.core.vfs.VfsNode
import com.minifile.explorer.core.vfs.VfsPath
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Pure-JVM Google Drive REST API v3 implementation of [FileSystem] for [VfsPath.GDrive] (§3).
 *
 * Contains zero `android.*`, `dalvik.*`, or `androidx.*` imports.
 */
class GoogleDriveFileSystem(
    private val serverLookup: (String) -> GDriveServerBookmark?,
    private val tokenProviderFor: (GDriveServerBookmark) -> DriveTokenProvider,
    apiBase: String = DEFAULT_DRIVE_API_BASE,
    uploadBase: String = DEFAULT_DRIVE_UPLOAD_BASE,
    private val clock: Clock = Clock.SYSTEM,
    private val logger: Logger = Logger.NOOP,
    baseBackoffMs: Long = 500L,
    maxJitterMs: Long = 250L,
) : FileSystem {

    companion object {
        const val RESUMABLE_CHUNK_BYTES = 8 * 1024 * 1024 // 8 MiB (multiple of 256 KiB)
        private const val INITIAL_UPLOAD_BUFFER_BYTES = 64 * 1024
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }

    internal val http = DriveHttp(
        apiBase = apiBase,
        uploadBase = uploadBase,
        logger = logger,
        baseBackoffMs = baseBackoffMs,
        maxJitterMs = maxJitterMs,
    )

    internal val resolver = DrivePathResolver(
        http = http,
        clock = clock,
    )

    private val providers = ConcurrentHashMap<String, DriveTokenProvider>()

    override fun supports(path: VfsPath): Boolean = path is VfsPath.GDrive

    private fun requireGDrive(path: VfsPath): VfsPath.GDrive =
        path as? VfsPath.GDrive ?: throw IllegalArgumentException("Expected GDrive path, got $path")

    private fun requireBookmarkAndProvider(serverId: String): Pair<GDriveServerBookmark, DriveTokenProvider> {
        val bookmark = serverLookup(serverId)
            ?: throw FileNotFoundException("Google Drive server not found: $serverId")
        val provider = providers.computeIfAbsent(serverId) { tokenProviderFor(bookmark) }
        return bookmark to provider
    }

    fun peekCachedToken(serverId: String): String? =
        (providers[serverId] as? LockedTokenProvider)?.peekCachedToken()

    fun invalidate(path: VfsPath.GDrive) {
        resolver.invalidate(path)
    }

    fun invalidateServer(serverId: String) {
        providers.remove(serverId)
        resolver.invalidateServer(serverId)
    }

    // =========================================================================
    // Path manipulation
    // =========================================================================

    override suspend fun child(dir: VfsPath, name: String): VfsPath {
        val gd = requireGDrive(dir)
        val clean = name.trim('/')
        if (clean.isEmpty()) return gd
        val joined = if (gd.normalizedRemotePath == "/") {
            "/$clean"
        } else {
            "${gd.normalizedRemotePath}/$clean"
        }
        return VfsPath.GDrive(gd.serverId, PathUtils.normalizePosixPath(joined))
    }

    override suspend fun parent(path: VfsPath): VfsPath? {
        val gd = requireGDrive(path)
        val norm = gd.normalizedRemotePath
        if (norm == "/") return null
        val slash = norm.lastIndexOf('/')
        val parentRemote = if (slash <= 0) "/" else norm.substring(0, slash)
        return VfsPath.GDrive(gd.serverId, parentRemote)
    }

    // =========================================================================
    // Metadata & Listing
    // =========================================================================

    override suspend fun stat(path: VfsPath): VfsNode? {
        val gd = requireGDrive(path)
        val (bookmark, provider) = try {
            requireBookmarkAndProvider(gd.serverId)
        } catch (_: FileNotFoundException) {
            return null
        }
        if (gd.normalizedRemotePath == "/") {
            val rootName = bookmark.name.ifBlank { bookmark.email.ifBlank { "Google Drive" } }
            return VfsNode(
                path = VfsPath.GDrive(gd.serverId, "/"),
                name = rootName,
                isDirectory = true,
                size = 0L,
                lastModified = 0L,
                mimeType = "inode/directory",
                isHidden = false,
                canRead = true,
                canWrite = true,
            )
        }
        return try {
            val resolved = resolver.resolve(gd, provider) ?: return null
            toVfsNode(gd, resolved.meta)
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override suspend fun exists(path: VfsPath): Boolean = stat(path) != null

    override suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode> {
        val gd = requireGDrive(dir)
        val (_, provider) = requireBookmarkAndProvider(gd.serverId)
        val entries = resolver.listDirectory(gd, provider)
        val out = ArrayList<VfsNode>(entries.size)
        for (entry in entries) {
            val disp = entry.displayName.ifEmpty { toDisplayName(entry.name, entry.id) }
            if (!includeHidden && PathUtils.isHiddenName(disp)) continue
            val childPath = if (gd.normalizedRemotePath == "/") {
                VfsPath.GDrive(gd.serverId, "/$disp")
            } else {
                VfsPath.GDrive(gd.serverId, "${gd.normalizedRemotePath}/$disp")
            }
            out.add(toVfsNode(childPath, entry))
        }
        return out
    }

    override suspend fun space(path: VfsPath): Pair<Long, Long>? {
        val gd = requireGDrive(path)
        val (_, provider) = requireBookmarkAndProvider(gd.serverId)
        val json = http.getJson(
            tokenProvider = provider,
            pathSegments = listOf("about"),
            queryParams = listOf("fields" to "storageQuota(limit,usage),user(emailAddress)"),
        )
        val quota = json.obj("storageQuota") ?: return null
        val limit = quota.str("limit")?.toLongOrNull() ?: quota.long("limit", -1L)
        if (limit <= 0L) return null // Unlimited Workspace quota
        val usage = (quota.str("usage")?.toLongOrNull() ?: quota.long("usage", 0L)).coerceAtLeast(0L)
        val free = (limit - usage).coerceAtLeast(0L)
        return limit to free
    }

    // =========================================================================
    // Mutations: Create, Rename, Move, Delete, SetLastModified
    // =========================================================================

    override suspend fun createDirectory(dir: VfsPath, name: String): VfsPath {
        val gdDir = requireGDrive(dir)
        requireValidDriveVfsPath(gdDir, allowRoot = true)
        requireValidDriveTargetName(name)
        val (_, provider) = requireBookmarkAndProvider(gdDir.serverId)

        val parentMeta = resolveDirectoryTargetSuspend(gdDir, provider)
        val targetPath = child(gdDir, name) as VfsPath.GDrive

        // Idempotent mkdir -p check (cached first, then uncached)
        val cachedExisting = resolver.resolve(targetPath, provider)
        if (cachedExisting != null) {
            if (cachedExisting.meta.isFolder) return targetPath
            throw IOException("A file named '$name' already exists")
        }
        val uncachedExisting = resolver.queryExactChildUncached(parentMeta.id, name, provider)
        if (uncachedExisting != null) {
            resolver.onCreatedOrUploaded(targetPath, uncachedExisting)
            if (uncachedExisting.isFolder) return targetPath
            throw IOException("A file named '$name' already exists")
        }

        val body = MiniJson.obj(
            "name" to JsonValue.Str(name),
            "mimeType" to JsonValue.Str(FOLDER_MIME),
            "parents" to JsonValue.Arr(listOf(JsonValue.Str(parentMeta.id))),
        )
        val resp = http.postJson(
            tokenProvider = provider,
            pathSegments = listOf("files"),
            queryParams = listOf("fields" to FILE_FIELDS),
            body = body,
        )
        val createdMeta = parseDriveEntryMeta(resp)
            ?: throw IOException("Google Drive createDirectory returned invalid metadata")
        resolver.onCreatedOrUploaded(targetPath, createdMeta)
        return targetPath
    }

    override suspend fun createFile(dir: VfsPath, name: String, mime: String): VfsPath {
        val gdDir = requireGDrive(dir)
        requireValidDriveVfsPath(gdDir, allowRoot = true)
        requireValidDriveTargetName(name)
        val (_, provider) = requireBookmarkAndProvider(gdDir.serverId)

        val parentMeta = resolveDirectoryTargetSuspend(gdDir, provider)
        val targetPath = child(gdDir, name) as VfsPath.GDrive

        val cachedExisting = resolver.resolve(targetPath, provider)
        if (cachedExisting != null) {
            if (cachedExisting.meta.isFolder) {
                throw IOException("Cannot overwrite folder '$name' with a file")
            }
            return targetPath
        }
        val uncachedExisting = resolver.queryExactChildUncached(parentMeta.id, name, provider)
        if (uncachedExisting != null) {
            resolver.onCreatedOrUploaded(targetPath, uncachedExisting)
            if (uncachedExisting.isFolder) {
                throw IOException("Cannot overwrite folder '$name' with a file")
            }
            return targetPath
        }

        val safeMime = if (mime.startsWith(GOOGLE_APPS_MIME_PREFIX) || mime == "inode/directory") {
            PathUtils.guessMimeType(name)
        } else {
            mime.ifBlank { PathUtils.guessMimeType(name) }
        }
        val body = MiniJson.obj(
            "name" to JsonValue.Str(name),
            "mimeType" to JsonValue.Str(safeMime),
            "parents" to JsonValue.Arr(listOf(JsonValue.Str(parentMeta.id))),
        )
        val resp = http.postJson(
            tokenProvider = provider,
            pathSegments = listOf("files"),
            queryParams = listOf("fields" to FILE_FIELDS),
            body = body,
        )
        val createdMeta = parseDriveEntryMeta(resp)
            ?: throw IOException("Google Drive createFile returned invalid metadata")
        resolver.onCreatedOrUploaded(targetPath, createdMeta)
        return targetPath
    }

    override suspend fun rename(path: VfsPath, newName: String): VfsPath {
        val gd = requireGDrive(path)
        requireValidDriveVfsPath(gd, allowRoot = false)
        requireValidDriveTargetName(newName)
        if (gd.normalizedRemotePath == "/") {
            throw IOException("Cannot rename Google Drive root")
        }
        val (_, provider) = requireBookmarkAndProvider(gd.serverId)

        return withStaleRetrySuspend(gd, provider) { entry ->
            val driveTargetName = if (entry.isWorkspaceDoc && newName.endsWith(".pdf", ignoreCase = true)) {
                val stripped = newName.substring(0, newName.length - 4)
                requireValidDriveTargetName(stripped)
                stripped
            } else {
                newName
            }

            val resp = http.patchJson(
                tokenProvider = provider,
                pathSegments = listOf("files", entry.id),
                queryParams = listOf("fields" to FILE_FIELDS),
                body = MiniJson.obj("name" to JsonValue.Str(driveTargetName)),
            )
            val updated = parseDriveEntryMeta(resp)
                ?: entry.copy(name = driveTargetName)
            val sanNew = toDisplayName(updated.name, updated.id)
            val finalDisplay = if (updated.isWorkspaceDoc) "$sanNew.pdf" else sanNew
            val parentDir = parent(gd) as VfsPath.GDrive
            val newPath = child(parentDir, finalDisplay) as VfsPath.GDrive
            resolver.onRenamed(gd, entry, newPath, updated)
            newPath
        }
    }

    override suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath? {
        val gdSrc = src as? VfsPath.GDrive ?: return null
        val gdDst = dstDir as? VfsPath.GDrive ?: return null
        if (gdSrc.serverId != gdDst.serverId) return null

        requireValidDriveVfsPath(gdSrc, allowRoot = false)
        requireValidDriveVfsPath(gdDst, allowRoot = true)
        if (gdSrc.normalizedRemotePath == "/") {
            throw IOException("Invalid name")
        }

        val (_, provider) = requireBookmarkAndProvider(gdSrc.serverId)
        val srcParentPath = parent(gdSrc) as? VfsPath.GDrive
            ?: throw IOException("Cannot move Google Drive root")
        if (srcParentPath == gdDst) return gdSrc

        val srcParentMeta = resolveDirectoryTargetSuspend(srcParentPath, provider)
        val dstDirMeta = resolveDirectoryTargetSuspend(gdDst, provider)

        return withStaleRetrySuspend(gdSrc, provider) { srcEntry ->
            if (!isValidDriveTargetName(srcEntry.name)) {
                throw IOException("Invalid name")
            }
            val resp = http.patchJson(
                tokenProvider = provider,
                pathSegments = listOf("files", srcEntry.id),
                queryParams = listOf(
                    "addParents" to dstDirMeta.id,
                    "removeParents" to srcParentMeta.id,
                    "fields" to FILE_FIELDS,
                ),
                body = MiniJson.obj(),
            )
            val moved = parseDriveEntryMeta(resp) ?: srcEntry
            resolver.onMoved(gdSrc, srcEntry, gdDst, moved)
        }
    }

    override suspend fun delete(path: VfsPath): Boolean {
        val gd = requireGDrive(path)
        if (gd.normalizedRemotePath == "/") return false
        val (_, provider) = try {
            requireBookmarkAndProvider(gd.serverId)
        } catch (_: FileNotFoundException) {
            return false
        }

        val resolved = try {
            resolver.resolve(gd, provider)
        } catch (_: FileNotFoundException) {
            null
        } ?: return false

        suspend fun trashOrDeleteById(entry: DriveEntryMeta): Boolean {
            return try {
                http.patchJson(
                    tokenProvider = provider,
                    pathSegments = listOf("files", entry.id),
                    queryParams = listOf("fields" to "id,trashed"),
                    body = MiniJson.obj("trashed" to JsonValue.Bool(true)),
                )
                resolver.onDeleted(gd, entry)
                true
            } catch (apiErr: DriveApiException) {
                if (apiErr.status == 403) {
                    try {
                        http.delete(
                            tokenProvider = provider,
                            pathSegments = listOf("files", entry.id),
                        )
                        resolver.onDeleted(gd, entry)
                        true
                    } catch (delErr: DriveApiException) {
                        if (delErr.status == 403) false else throw delErr
                    } catch (_: DriveFileNotFoundException) {
                        resolver.onDeleted(gd, entry)
                        true
                    }
                } else {
                    throw apiErr
                }
            }
        }

        return try {
            trashOrDeleteById(resolved.meta)
        } catch (_: DriveFileNotFoundException) {
            if (resolved.fromCache) {
                resolver.evictPathAndParentListing(gd)
                val fresh = resolver.resolve(gd, provider, allowCache = false)
                if (fresh == null) {
                    true
                } else {
                    trashOrDeleteById(fresh.meta)
                }
            } else {
                resolver.onDeleted(gd, resolved.meta)
                true
            }
        }
    }

    override suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean {
        val gd = requireGDrive(path)
        if (gd.normalizedRemotePath == "/") return false
        return try {
            val (_, provider) = requireBookmarkAndProvider(gd.serverId)
            withStaleRetrySuspend(gd, provider) { entry ->
                if (entry.isWorkspaceDoc || !entry.canWrite) return@withStaleRetrySuspend false
                http.patchJson(
                    tokenProvider = provider,
                    pathSegments = listOf("files", entry.id),
                    queryParams = listOf("fields" to "id,modifiedTime"),
                    body = MiniJson.obj("modifiedTime" to JsonValue.Str(formatRfc3339(epochMs))),
                )
                resolver.onLastModifiedUpdated(gd, epochMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // Streaming I/O: openInput & openOutput
    // =========================================================================

    override fun openInput(path: VfsPath, offset: Long): InputStream {
        require(offset >= 0L) { "Negative offset: $offset" }
        val gd = requireGDrive(path)
        if (gd.normalizedRemotePath == "/") {
            throw IOException("Cannot open Google Drive root for reading")
        }
        val (_, provider) = requireBookmarkAndProvider(gd.serverId)

        var resolved = resolver.resolveBlocking(gd, provider)
            ?: throw DriveFileNotFoundException("Google Drive file not found: ${gd.normalizedRemotePath}")

        fun openForEntry(entry: DriveEntryMeta): InputStream {
            val target = if (entry.isShortcut) {
                resolver.resolveShortcutTargetBlocking(gd, entry, provider)
            } else {
                entry
            }
            if (target.isFolder) {
                throw IOException("Cannot open folder as file: ${gd.normalizedRemotePath}")
            }
            if (!target.canRead) {
                throw IOException("Unsupported Google Drive item (${target.mimeType}): ${gd.normalizedRemotePath}")
            }

            val isExport = target.isWorkspaceDoc
            val exportMime = if (isExport) (WORKSPACE_EXPORT[target.mimeType] ?: PDF_MIME) else null
            val opened = http.openDownloadBlocking(
                tokenProvider = provider,
                fileId = target.id,
                isExport = isExport,
                exportMimeType = exportMime,
                rangeOffset = if (isExport) 0L else offset,
            )

            val wrapped = BufferedInputStream(opened.stream, DOWNLOAD_BUFFER_BYTES)
            // Manual skip fallback when server answered 200 instead of 206, or on non-rangeable Workspace export
            if (offset > 0L && (isExport || opened.status == 200)) {
                try {
                    skipFully(wrapped, offset)
                } catch (t: Throwable) {
                    runCatching { wrapped.close() }
                    runCatching { opened.disconnect() }
                    throw t
                }
            }

            val initialExpectedBytes = when {
                opened.contentLength < 0L -> -1L
                offset > 0L && (isExport || opened.status == 200) ->
                    (opened.contentLength - offset).coerceAtLeast(0L)
                else -> opened.contentLength
            }

            return DriveInputStream(
                pathLabel = gd.normalizedRemotePath,
                fileId = target.id,
                initialOffset = offset,
                initialExpectedBytes = initialExpectedBytes,
                canReconnect = !isExport,
                tokenProvider = provider,
                http = http,
                initialStream = wrapped,
                initialDisconnect = opened.disconnect,
            )
        }

        return try {
            openForEntry(resolved.meta)
        } catch (nf: DriveFileNotFoundException) {
            if (resolved.fromCache) {
                resolver.evictPathAndParentListing(gd)
                resolved = resolver.resolveBlocking(gd, provider, allowCache = false) ?: throw nf
                openForEntry(resolved.meta)
            } else {
                throw nf
            }
        }
    }

    override fun openOutput(path: VfsPath, append: Boolean): OutputStream {
        val gd = requireGDrive(path)
        requireValidDriveVfsPath(gd, allowRoot = false)
        if (gd.normalizedRemotePath == "/") {
            throw IOException("Cannot open Google Drive root for writing")
        }
        val (_, provider) = requireBookmarkAndProvider(gd.serverId)
        val leafName = gd.displayName
        requireValidDriveTargetName(leafName)
        val guessedMime = PathUtils.guessMimeType(leafName)

        var resolved = resolver.resolveBlocking(gd, provider)

        fun startPatchSession(entry: DriveEntryMeta): Pair<DriveHttp.ResumableSessionStart, DriveEntryMeta> {
            val target = if (entry.isShortcut) {
                resolver.resolveShortcutTargetBlocking(gd, entry, provider)
            } else {
                entry
            }
            if (target.isFolder) {
                throw IOException("Cannot open directory for writing: ${gd.normalizedRemotePath}")
            }
            if (target.isWorkspaceDoc || !target.canWrite) {
                throw IOException("Read-only Google Docs item")
            }
            val session = http.startResumableUploadBlocking(
                tokenProvider = provider,
                existingFileId = target.id,
                metadataBody = null,
                uploadContentType = guessedMime,
            )
            return session to target
        }

        var sessionAndExisting: Pair<DriveHttp.ResumableSessionStart, DriveEntryMeta?>? = null
        if (resolved != null) {
            try {
                val (sess, target) = startPatchSession(resolved.meta)
                sessionAndExisting = sess to target
            } catch (nf: DriveFileNotFoundException) {
                if (resolved.fromCache) {
                    resolver.evictPathAndParentListing(gd)
                    resolved = null
                } else {
                    throw nf
                }
            }
        }

        if (sessionAndExisting == null) {
            val parentPath = VfsPath.GDrive(
                gd.serverId,
                gd.normalizedRemotePath.substringBeforeLast('/').ifEmpty { "/" },
            )
            val parentMeta = resolveDirectoryTargetBlocking(parentPath, provider)

            // M5: Single uncached pre-check before creating to avoid duplicate Drive files
            val existingRemote = resolver.queryExactChildUncachedBlocking(parentMeta.id, leafName, provider)
            if (existingRemote != null) {
                resolver.onCreatedOrUploaded(gd, existingRemote)
                val (sess, target) = startPatchSession(existingRemote)
                sessionAndExisting = sess to target
            } else {
                val createMetaBody = MiniJson.obj(
                    "name" to JsonValue.Str(leafName),
                    "parents" to JsonValue.Arr(listOf(JsonValue.Str(parentMeta.id))),
                )
                val sess = http.startResumableUploadBlocking(
                    tokenProvider = provider,
                    existingFileId = null,
                    metadataBody = createMetaBody,
                    uploadContentType = guessedMime,
                )
                if (sess.initialMeta != null) {
                    resolver.onCreatedOrUploaded(gd, sess.initialMeta)
                }
                sessionAndExisting = sess to sess.initialMeta
            }
        }

        val (session, priorMeta) = sessionAndExisting
        val outStream = DriveResumableOutputStream(
            path = gd,
            sessionUri = session.sessionUri,
            contentType = guessedMime,
            fallbackName = priorMeta?.name ?: leafName,
            fallbackId = priorMeta?.id,
            http = http,
            resolver = resolver,
            clock = clock,
        )

        if (append && priorMeta != null && (priorMeta.size > 0L || priorMeta.id.isNotEmpty())) {
            try {
                // Pump existing remote content through the 8 MiB resumable upload stream
                val opened = http.openDownloadBlocking(
                    tokenProvider = provider,
                    fileId = priorMeta.id,
                    isExport = false,
                    exportMimeType = null,
                    rangeOffset = 0L,
                )
                try {
                    val pumpBuf = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    opened.stream.use { input ->
                        while (true) {
                            val n = input.read(pumpBuf)
                            if (n < 0) break
                            if (n > 0) outStream.write(pumpBuf, 0, n)
                        }
                    }
                } finally {
                    opened.disconnect()
                }
            } catch (_: DriveFileNotFoundException) {
                // File had no prior bytes yet; proceed cleanly
            } catch (t: Throwable) {
                runCatching { outStream.abortWithoutCommit() }
                throw t
            }
        }

        return outStream
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private suspend fun resolveDirectoryTargetSuspend(
        dir: VfsPath.GDrive,
        provider: DriveTokenProvider,
    ): DriveEntryMeta {
        val resolved = resolver.resolve(dir, provider)
            ?: throw DriveFileNotFoundException("Google Drive folder not found: ${dir.normalizedRemotePath}")
        val target = if (resolved.meta.isShortcut) {
            resolver.resolveShortcutTarget(dir, resolved.meta, provider)
        } else {
            resolved.meta
        }
        if (!target.isFolder) {
            throw IOException("Not a directory: ${dir.normalizedRemotePath}")
        }
        return target
    }

    private fun resolveDirectoryTargetBlocking(
        dir: VfsPath.GDrive,
        provider: DriveTokenProvider,
    ): DriveEntryMeta {
        val resolved = resolver.resolveBlocking(dir, provider)
            ?: throw DriveFileNotFoundException("Google Drive folder not found: ${dir.normalizedRemotePath}")
        val target = if (resolved.meta.isShortcut) {
            resolver.resolveShortcutTargetBlocking(dir, resolved.meta, provider)
        } else {
            resolved.meta
        }
        if (!target.isFolder) {
            throw IOException("Not a directory: ${dir.normalizedRemotePath}")
        }
        return target
    }

    private suspend fun <T> withStaleRetrySuspend(
        path: VfsPath.GDrive,
        provider: DriveTokenProvider,
        block: suspend (DriveEntryMeta) -> T,
    ): T {
        val resolved = resolver.resolve(path, provider)
            ?: throw DriveFileNotFoundException("Google Drive item not found: ${path.normalizedRemotePath}")
        return try {
            block(resolved.meta)
        } catch (nf: DriveFileNotFoundException) {
            if (resolved.fromCache) {
                resolver.evictPathAndParentListing(path)
                val fresh = resolver.resolve(path, provider, allowCache = false) ?: throw nf
                block(fresh.meta)
            } else {
                throw nf
            }
        }
    }

    private fun toVfsNode(path: VfsPath.GDrive, meta: DriveEntryMeta): VfsNode {
        val disp = meta.displayName.ifEmpty { path.displayName }
        val effectiveMime = when {
            meta.isFolder -> "inode/directory"
            meta.isWorkspaceDoc -> WORKSPACE_EXPORT[meta.mimeType] ?: PDF_MIME
            else -> meta.mimeType
        }
        return VfsNode(
            path = path,
            name = disp,
            isDirectory = meta.isFolder,
            size = meta.size,
            lastModified = meta.modifiedTimeMs,
            mimeType = effectiveMime,
            isHidden = PathUtils.isHiddenName(disp),
            canRead = meta.canRead,
            canWrite = meta.canWrite,
            isSymlink = meta.isShortcut,
            symlinkTarget = meta.shortcutTargetId,
        )
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(8192)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val toRead = min(remaining, scratch.size.toLong()).toInt()
            val n = stream.read(scratch, 0, toRead)
            if (n < 0) break
            remaining -= n
        }
    }

    // =========================================================================
    // DriveInputStream: single Range reconnect on mid-body IOException (M6)
    // =========================================================================

    private class DriveInputStream(
        private val pathLabel: String,
        private val fileId: String,
        private val initialOffset: Long,
        initialExpectedBytes: Long,
        private val canReconnect: Boolean,
        private val tokenProvider: DriveTokenProvider,
        private val http: DriveHttp,
        initialStream: InputStream,
        initialDisconnect: () -> Unit,
    ) : InputStream() {

        private var delegate: InputStream = initialStream
        private var disconnectFn: () -> Unit = initialDisconnect
        private var expectedTotalBytes: Long = initialExpectedBytes
        private var bytesDelivered: Long = 0L
        private var reconnectedOnce: Boolean = false
        private var closed: Boolean = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream closed")
            if (len == 0) return 0
            return try {
                val n = delegate.read(b, off, len)
                if (n > 0) {
                    bytesDelivered += n
                    n
                } else if (n < 0 && expectedTotalBytes >= 0L && bytesDelivered < expectedTotalBytes) {
                    throw EOFException("Premature EOF after $bytesDelivered of $expectedTotalBytes bytes")
                } else {
                    n
                }
            } catch (firstErr: IOException) {
                reconnectAndRead(b, off, len, firstErr)
            }
        }

        private fun reconnectAndRead(
            b: ByteArray,
            off: Int,
            len: Int,
            cause: IOException,
        ): Int {
            if (!canReconnect || reconnectedOnce) {
                throw actionableDownloadError(cause)
            }
            reconnectedOnce = true
            closeCurrentQuietly()

            val resumeOffset = initialOffset + bytesDelivered
            val reopened = try {
                http.openDownloadBlocking(
                    tokenProvider = tokenProvider,
                    fileId = fileId,
                    isExport = false,
                    exportMimeType = null,
                    rangeOffset = resumeOffset,
                    requireRangeHeader = true,
                )
            } catch (reopenErr: IOException) {
                throw actionableDownloadError(reopenErr)
            }

            if (reopened.status != 206) {
                runCatching { reopened.stream.close() }
                runCatching { reopened.disconnect() }
                throw actionableDownloadError(
                    IOException("Expected HTTP 206 Partial Content on Range reconnect, got ${reopened.status}"),
                )
            }

            if (reopened.contentLength >= 0L) {
                expectedTotalBytes = bytesDelivered + reopened.contentLength
            }
            delegate = BufferedInputStream(reopened.stream, DOWNLOAD_BUFFER_BYTES)
            disconnectFn = reopened.disconnect

            return try {
                val n = delegate.read(b, off, len)
                if (n > 0) {
                    bytesDelivered += n
                    n
                } else if (n < 0 && expectedTotalBytes >= 0L && bytesDelivered < expectedTotalBytes) {
                    throw EOFException("Premature EOF after $bytesDelivered of $expectedTotalBytes bytes")
                } else {
                    n
                }
            } catch (secondErr: IOException) {
                throw actionableDownloadError(secondErr)
            }
        }

        private fun actionableDownloadError(cause: IOException): IOException =
            IOException(
                "Google Drive download failed: $pathLabel — ${cause.message ?: cause::class.java.simpleName}",
                cause,
            )

        private fun closeCurrentQuietly() {
            runCatching { delegate.close() }
            runCatching { disconnectFn() }
        }

        override fun close() {
            if (closed) return
            closed = true
            closeCurrentQuietly()
        }
    }

    // =========================================================================
    // DriveResumableOutputStream: bounded <= 8 MiB chunked PUT uploader (§3.3, B2)
    // =========================================================================

    private class DriveResumableOutputStream(
        private val path: VfsPath.GDrive,
        private val sessionUri: String,
        private val contentType: String,
        private val fallbackName: String,
        private val fallbackId: String?,
        private val http: DriveHttp,
        private val resolver: DrivePathResolver,
        private val clock: Clock,
    ) : OutputStream() {

        private var buffer: ByteArray? = ByteArray(INITIAL_UPLOAD_BUFFER_BYTES)
        private var bufferedCount: Int = 0
        private var uploadedOffset: Long = 0L
        private var completedMeta: DriveEntryMeta? = null
        private var closed: Boolean = false
        private var aborted: Boolean = false

        fun abortWithoutCommit() {
            aborted = true
            closed = true
            buffer = null
        }

        override fun write(b: Int) {
            val one = byteArrayOf(b.toByte())
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("Upload stream already closed")
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
            }
            if (len == 0) return

            var srcPos = off
            var remaining = len
            while (remaining > 0) {
                val space = RESUMABLE_CHUNK_BYTES - bufferedCount
                val toCopy = min(remaining, space)
                ensureCapacity(bufferedCount + toCopy)
                System.arraycopy(b, srcPos, buffer!!, bufferedCount, toCopy)
                bufferedCount += toCopy
                srcPos += toCopy
                remaining -= toCopy

                if (bufferedCount == RESUMABLE_CHUNK_BYTES) {
                    flushFullChunk()
                }
            }
        }

        override fun flush() {
            if (closed) throw IOException("Upload stream already closed")
            // Intermediate chunks to Google Drive MUST be multiples of 256 KiB (here 8 MiB);
            // partial chunks are only valid on the final PUT in close().
        }

        override fun close() {
            if (closed) return
            closed = true
            if (aborted) {
                buffer = null
                return
            }

            try {
                val totalBytes = uploadedOffset + bufferedCount.toLong()
                if (completedMeta == null) {
                    val buf = buffer ?: ByteArray(0)
                    val finalObj = sendChunkWithResume(
                        chunkBuf = buf,
                        chunkStart = uploadedOffset,
                        chunkLen = bufferedCount,
                        totalSize = totalBytes,
                    )
                    completedMeta = finalObj?.let(::parseDriveEntryMeta) ?: DriveEntryMeta(
                        id = fallbackId ?: "uploaded-${clock.nowMs()}",
                        name = fallbackName,
                        mimeType = contentType,
                        isFolder = false,
                        size = totalBytes,
                        modifiedTimeMs = clock.nowMs(),
                        canWrite = true,
                        isShortcut = false,
                        shortcutTargetId = null,
                        isWorkspaceDoc = false,
                    )
                }
                resolver.onCreatedOrUploaded(path, completedMeta!!)
            } finally {
                buffer = null
                bufferedCount = 0
            }
        }

        private fun ensureCapacity(needed: Int) {
            val cur = buffer ?: ByteArray(INITIAL_UPLOAD_BUFFER_BYTES).also { buffer = it }
            if (cur.size >= needed) return
            var newCap = cur.size
            while (newCap < needed) {
                newCap = (newCap * 2).coerceAtMost(RESUMABLE_CHUNK_BYTES)
            }
            val grown = ByteArray(newCap)
            if (bufferedCount > 0) {
                System.arraycopy(cur, 0, grown, 0, bufferedCount)
            }
            buffer = grown
        }

        private fun flushFullChunk() {
            val buf = buffer ?: return
            val completedObj = sendChunkWithResume(
                chunkBuf = buf,
                chunkStart = uploadedOffset,
                chunkLen = bufferedCount,
                totalSize = null,
            )
            uploadedOffset += bufferedCount.toLong()
            bufferedCount = 0
            if (completedObj != null) {
                completedMeta = parseDriveEntryMeta(completedObj)
            }
        }

        /**
         * Uploads `chunkBuf[0 until chunkLen]` starting at `chunkStart`, querying `bytes * / *`
         * and resuming from the server's `Range` header if a chunk PUT drops or fails transiently.
         */
        private fun sendChunkWithResume(
            chunkBuf: ByteArray,
            chunkStart: Long,
            chunkLen: Int,
            totalSize: Long?,
        ): JsonValue.Obj? {
            var localSkip = 0
            var attempt = 0

            while (true) {
                val sliceLen = chunkLen - localSkip
                val sliceStart = chunkStart + localSkip.toLong()
                val totalPart = totalSize?.toString() ?: "*"
                val contentRange = if (sliceLen == 0) {
                    "bytes */$totalPart"
                } else {
                    val sliceEnd = sliceStart + sliceLen.toLong() - 1L
                    "bytes $sliceStart-$sliceEnd/$totalPart"
                }

                try {
                    val res = http.putResumableChunkSingleAttemptBlocking(
                        sessionUri = sessionUri,
                        buffer = chunkBuf,
                        offset = localSkip,
                        length = sliceLen,
                        contentRange = contentRange,
                        contentType = contentType,
                    )
                    if (res.status == 308) {
                        if (totalSize != null) {
                            // Server still reports incomplete on final chunk — query/retry remaining bytes
                            val committed = parseCommittedBytesFromRange(res.rangeHeader)
                            if (committed < totalSize && attempt + 1 < DriveHttp.MAX_ATTEMPTS) {
                                localSkip = (committed - chunkStart).coerceIn(0L, chunkLen.toLong()).toInt()
                                attempt++
                                continue
                            }
                            throw IOException("Google Drive upload incomplete after final chunk (Range=${res.rangeHeader})")
                        }
                        return null
                    }
                    return res.completedJson
                } catch (err: IOException) {
                    val isRetryable = err is DriveHttp.RetryableUploadChunkException ||
                        err is SocketTimeoutException ||
                        (err !is GDriveAuthException && err !is DriveApiException && err !is DriveFileNotFoundException)
                    if (!isRetryable || attempt + 1 >= DriveHttp.MAX_ATTEMPTS) {
                        throw err
                    }

                    http.sleepBlocking(http.computeBackoffMs(attempt))
                    attempt++

                    // Query server upload progress (`Content-Range: bytes */<totalOrStar>`)
                    val queryRange = "bytes */${totalSize?.toString() ?: "*"}"
                    val statusRes = try {
                        http.putResumableChunkSingleAttemptBlocking(
                            sessionUri = sessionUri,
                            buffer = chunkBuf,
                            offset = 0,
                            length = 0,
                            contentRange = queryRange,
                            contentType = contentType,
                        )
                    } catch (_: IOException) {
                        continue
                    }

                    if (statusRes.status == 200 || statusRes.status == 201) {
                        return statusRes.completedJson
                    }
                    if (statusRes.status == 308) {
                        val committed = parseCommittedBytesFromRange(statusRes.rangeHeader)
                        localSkip = (committed - chunkStart).coerceIn(0L, chunkLen.toLong()).toInt()
                        if (totalSize == null && localSkip >= chunkLen) {
                            // Full intermediate chunk was actually received by the server!
                            return null
                        }
                    }
                }
            }
        }

        private fun parseCommittedBytesFromRange(rangeHeader: String?): Long {
            // Format: "bytes=0-8388607" -> 8388608 bytes committed
            if (rangeHeader.isNullOrBlank()) return 0L
            val dash = rangeHeader.lastIndexOf('-')
            if (dash < 0 || dash + 1 >= rangeHeader.length) return 0L
            val endInclusive = rangeHeader.substring(dash + 1).trim().toLongOrNull() ?: return 0L
            return (endInclusive + 1L).coerceAtLeast(0L)
        }
    }
}
