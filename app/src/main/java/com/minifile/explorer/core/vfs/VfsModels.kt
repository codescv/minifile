package com.minifile.explorer.core.vfs

/**
 * Opaque, serializable virtual filesystem location.
 * Pure Kotlin (no android.* imports).
 */
sealed interface VfsPath {
    val displayName: String

    data class Local(val absolutePath: String) : VfsPath {
        val normalized: String = PathUtils.normalizeLocalPath(absolutePath)

        override val displayName: String
            get() {
                if (normalized == "/") return "/"
                return normalized.substringAfterLast('/')
            }

        override fun equals(other: Any?): Boolean =
            other is Local && normalized == other.normalized

        override fun hashCode(): Int = normalized.hashCode()
    }

    data class Saf(val treeUri: String, val documentId: String) : VfsPath {
        override val displayName: String
            get() {
                val tail = documentId.substringAfterLast(':', documentId)
                    .trim('/')
                if (tail.isEmpty()) return treeUri.substringAfterLast('/').ifEmpty { "SAF" }
                return tail.substringAfterLast('/')
            }
    }

    data class Ftp(val serverId: String, val remotePath: String = "/") : VfsPath {
        val normalizedRemotePath: String = PathUtils.normalizeLocalPath(remotePath)

        override val displayName: String
            get() {
                if (normalizedRemotePath == "/") return "/"
                return normalizedRemotePath.substringAfterLast('/')
            }

        override fun equals(other: Any?): Boolean =
            other is Ftp && serverId == other.serverId && normalizedRemotePath == other.normalizedRemotePath

        override fun hashCode(): Int = 31 * serverId.hashCode() + normalizedRemotePath.hashCode()
    }

    data class GDrive(val serverId: String, val remotePath: String = "/") : VfsPath {
        val normalizedRemotePath: String = PathUtils.normalizePosixPath(remotePath)

        override val displayName: String
            get() {
                if (normalizedRemotePath == "/") return "/"
                return normalizedRemotePath.substringAfterLast('/')
            }

        override fun equals(other: Any?): Boolean =
            other is GDrive && serverId == other.serverId && normalizedRemotePath == other.normalizedRemotePath

        override fun hashCode(): Int = 31 * serverId.hashCode() + normalizedRemotePath.hashCode()
    }

    fun serialize(): String = when (this) {
        is Local -> "file://$normalized"
        is Saf -> "saf:$treeUri#$documentId"
        is Ftp -> "ftp://$serverId$normalizedRemotePath"
        is GDrive -> "gdrive://$serverId$normalizedRemotePath"
    }

    companion object {
        fun parse(serialized: String): VfsPath {
            return when {
                serialized.startsWith("file://") -> {
                    val raw = serialized.removePrefix("file://").ifEmpty { "/" }
                    Local(PathUtils.normalizeLocalPath(raw))
                }

                serialized.startsWith("saf:") -> {
                    val rest = serialized.removePrefix("saf:")
                    val sep = rest.indexOf('#')
                    require(sep > 0) { "Invalid SAF serialized path: $serialized" }
                    Saf(
                        treeUri = rest.substring(0, sep),
                        documentId = rest.substring(sep + 1),
                    )
                }

                serialized.startsWith("ftp://") -> {
                    val rest = serialized.removePrefix("ftp://")
                    require(rest.isNotEmpty()) { "Invalid FTP serialized path: $serialized" }
                    val slash = rest.indexOf('/')
                    if (slash < 0) {
                        Ftp(serverId = rest, remotePath = "/")
                    } else {
                        val serverId = rest.substring(0, slash)
                        require(serverId.isNotEmpty()) { "Invalid FTP serverId in serialized path: $serialized" }
                        val remote = rest.substring(slash)
                        Ftp(serverId = serverId, remotePath = PathUtils.normalizeLocalPath(remote))
                    }
                }

                serialized.startsWith("gdrive://") -> {
                    val rest = serialized.removePrefix("gdrive://")
                    require(rest.isNotEmpty()) { "Invalid Google Drive serialized path: $serialized" }
                    val slash = rest.indexOf('/')
                    if (slash < 0) {
                        GDrive(serverId = rest, remotePath = "/")
                    } else {
                        val serverId = rest.substring(0, slash)
                        require(serverId.isNotEmpty()) { "Invalid Google Drive serverId in serialized path: $serialized" }
                        val remote = rest.substring(slash)
                        GDrive(serverId = serverId, remotePath = PathUtils.normalizePosixPath(remote))
                    }
                }

                serialized.startsWith("/") -> Local(PathUtils.normalizeLocalPath(serialized))
                else -> throw IllegalArgumentException("Unrecognized VfsPath format: $serialized")
            }
        }
    }
}

data class VfsNode(
    val path: VfsPath,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String? = null,
    val isHidden: Boolean = PathUtils.isHiddenName(name),
    val canRead: Boolean = true,
    val canWrite: Boolean = true,
    val isSymlink: Boolean = false,
    val childCount: Int = -1,
    val hasNomedia: Boolean = false,
    val posixPermissions: String? = null,
    val symlinkTarget: String? = null,
) {
    val resolvedMimeType: String
        get() = mimeType ?: if (isDirectory) "inode/directory" else PathUtils.guessMimeType(name)
}

enum class VolumeKind {
    PRIMARY,
    SD_CARD,
    USB_OTG,
    APP_DATA,
    APP_OBB,
    MOUNTED_TREE,
}

data class Volume(
    val id: String,
    val name: String,
    val kind: VolumeKind,
    val root: VfsPath?,
    val totalBytes: Long = -1L,
    val freeBytes: Long = -1L,
    val isMounted: Boolean = true,
    val needsSafGrant: Boolean = false,
    val safInitialDocId: String? = null,
    val subtitle: String? = null,
)
