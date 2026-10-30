package com.minifile.explorer.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.core.vfs.Volume
import com.minifile.explorer.core.vfs.VolumeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class AndroidVolumeProvider(
    private val context: Context,
    private val treeStore: PersistedTreeStore,
) {
    private val appContext = context.applicationContext
    private val storageManager = appContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager

    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        treeStore.refreshFromSystem()
        val grants = treeStore.grants.value
        val allFiles = hasAllFilesAccess(appContext)
        val built = ArrayList<Volume>()

        val primaryDir = Environment.getExternalStorageDirectory()
        val (primaryTotal, primaryFree) = statSpace(primaryDir)
        built.add(
            Volume(
                id = "primary",
                name = "Internal",
                kind = VolumeKind.PRIMARY,
                root = VfsPath.Local(primaryDir.absolutePath),
                totalBytes = primaryTotal,
                freeBytes = primaryFree,
                isMounted = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED ||
                    Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED_READ_ONLY,
                needsSafGrant = !allFiles,
                subtitle = "Internal Storage",
            ),
        )

        // Secondary / removable volumes (SD Card, USB OTG)
        for (sv in storageManager.storageVolumes) {
            if (sv.isPrimary) continue
            val state = sv.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) {
                continue
            }
            val uuid = sv.uuid ?: sv.getDescription(appContext)
            val key = "vol:$uuid"
            val dir = resolveStorageVolumeDir(sv)
            val description = sv.getDescription(appContext)?.takeIf { it.isNotBlank() } ?: "External Drive"
            val lowerDesc = description.lowercase()
            val kind = if (lowerDesc.contains("usb") || lowerDesc.contains("otg") || lowerDesc.contains("flash")) {
                VolumeKind.USB_OTG
            } else {
                VolumeKind.SD_CARD
            }

            val grant = grants[key]
            val canReadLocal = dir != null && dir.exists() && dir.canRead()
            val canWriteLocal = dir != null && dir.canWrite()
            val (tot, free) = if (dir != null) statSpace(dir) else (-1L to -1L)

            val rootPath: VfsPath? = when {
                canWriteLocal -> VfsPath.Local(dir!!.absolutePath)
                grant != null -> grant.toVfsPath()
                canReadLocal -> VfsPath.Local(dir!!.absolutePath)
                else -> null
            }

            built.add(
                Volume(
                    id = key,
                    name = description,
                    kind = kind,
                    root = rootPath,
                    totalBytes = tot,
                    freeBytes = free,
                    isMounted = true,
                    needsSafGrant = rootPath == null || (!canWriteLocal && grant == null),
                    safInitialDocId = sv.uuid?.let { "$it:" },
                    subtitle = dir?.absolutePath ?: grant?.treeUri,
                ),
            )
        }

        // App Data: Android/data and Android/obb
        val androidDataFile = File(primaryDir, "Android/data")
        val canReadDataDirectly = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            allFiles &&
            androidDataFile.canRead()
        val dataGrant = grants[PersistedTreeStore.KEY_ANDROID_DATA]
        built.add(
            Volume(
                id = PersistedTreeStore.KEY_ANDROID_DATA,
                name = "Android/data",
                kind = VolumeKind.APP_DATA,
                root = when {
                    canReadDataDirectly -> VfsPath.Local(androidDataFile.absolutePath)
                    dataGrant != null -> dataGrant.toVfsPath()
                    else -> null
                },
                isMounted = true,
                needsSafGrant = !canReadDataDirectly && dataGrant == null,
                safInitialDocId = "primary:Android/data",
                subtitle = if (dataGrant != null || canReadDataDirectly) "App external data" else "Tap to mount via system picker",
            ),
        )

        val androidObbFile = File(primaryDir, "Android/obb")
        val canReadObbDirectly = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            allFiles &&
            androidObbFile.canRead()
        val obbGrant = grants[PersistedTreeStore.KEY_ANDROID_OBB]
        built.add(
            Volume(
                id = PersistedTreeStore.KEY_ANDROID_OBB,
                name = "Android/obb",
                kind = VolumeKind.APP_OBB,
                root = when {
                    canReadObbDirectly -> VfsPath.Local(androidObbFile.absolutePath)
                    obbGrant != null -> obbGrant.toVfsPath()
                    else -> null
                },
                isMounted = true,
                needsSafGrant = !canReadObbDirectly && obbGrant == null,
                safInitialDocId = "primary:Android/obb",
                subtitle = if (obbGrant != null || canReadObbDirectly) "App expansion files" else "Tap to mount via system picker",
            ),
        )

        // Generic Mounted SAF folders (and per-package grants)
        val reservedKeys = built.map { it.id }.toSet()
        for ((key, grant) in grants) {
            if (key in reservedKeys) continue
            built.add(
                Volume(
                    id = key,
                    name = grant.label,
                    kind = VolumeKind.MOUNTED_TREE,
                    root = grant.toVfsPath(),
                    isMounted = true,
                    needsSafGrant = false,
                    subtitle = "${grant.providerAuthority} · ${grant.rootDocumentId}",
                ),
            )
        }

        _volumes.value = built
    }

    fun buildOpenTreeIntent(volume: Volume? = null, initialDocId: String? = null): Intent {
        if (volume != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (volume.kind == VolumeKind.SD_CARD || volume.kind == VolumeKind.USB_OTG)
        ) {
            val uuid = volume.id.removePrefix("vol:")
            val match = storageManager.storageVolumes.firstOrNull {
                !it.isPrimary && (it.uuid == uuid || it.getDescription(appContext) == uuid)
            }
            if (match != null) {
                return match.createOpenDocumentTreeIntent().apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                }
            }
        }

        val docId = initialDocId ?: volume?.safInitialDocId
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            if (docId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri: Uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    docId,
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    private fun resolveStorageVolumeDir(sv: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return sv.directory
        }
        val uuid = sv.uuid ?: return null
        val candidate = File("/storage/$uuid")
        return if (candidate.exists()) candidate else null
    }

    private fun statSpace(dir: File): Pair<Long, Long> {
        return runCatching {
            val s = StatFs(dir.absolutePath)
            s.totalBytes to s.availableBytes
        }.getOrDefault(-1L to -1L)
    }

    companion object {
        fun hasAllFilesAccess(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
