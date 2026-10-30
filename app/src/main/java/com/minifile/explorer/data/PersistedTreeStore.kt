package com.minifile.explorer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.KeyValueStore
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.vfs.VfsPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SafGrant(
    val key: String,
    val treeUri: String,
    val rootDocumentId: String,
    val label: String,
    val providerAuthority: String,
    val isWritable: Boolean,
) {
    fun toVfsPath(): VfsPath.Saf = VfsPath.Saf(treeUri = treeUri, documentId = rootDocumentId)
}

class PersistedTreeStore(
    private val context: Context,
    private val store: KeyValueStore,
) {
    companion object {
        const val KEY_SAF_TREES = "saf_trees"
        const val KEY_SAF_LABELS = "saf_tree_labels"
        const val MOUNTED_PREFIX = "mounted:"
        const val KEY_ANDROID_DATA = "app_data"
        const val KEY_ANDROID_OBB = "app_obb"
    }

    private val lock = Any()
    private val _grants = MutableStateFlow<Map<String, SafGrant>>(emptyMap())
    val grants: StateFlow<Map<String, SafGrant>> = _grants.asStateFlow()

    init {
        refreshFromSystem()
    }

    fun refreshFromSystem() = synchronized(lock) {
        val persistedPerms = runCatching {
            context.contentResolver.persistedUriPermissions.associateBy { it.uri.toString() }
        }.getOrDefault(emptyMap())

        val storedMap = parseStoredMap()
        val storedLabels = parseStoredLabels()
        val valid = LinkedHashMap<String, SafGrant>()
        val cleanedMap = LinkedHashMap<String, String>()

        for ((key, uriStr) in storedMap) {
            val perm = persistedPerms[uriStr]
            if (perm != null && perm.isReadPermission) {
                val parsedUri = Uri.parse(uriStr)
                val docId = runCatching { DocumentsContract.getTreeDocumentId(parsedUri) }.getOrNull()
                if (docId != null) {
                    val label = storedLabels[key] ?: deriveDefaultLabel(parsedUri, docId)
                    valid[key] = SafGrant(
                        key = key,
                        treeUri = uriStr,
                        rootDocumentId = docId,
                        label = label,
                        providerAuthority = parsedUri.authority.orEmpty(),
                        isWritable = perm.isWritePermission,
                    )
                    cleanedMap[key] = uriStr
                }
            }
        }

        // Also surface any persisted tree permissions granted earlier in this app that weren't in prefs
        for ((uriStr, perm) in persistedPerms) {
            if (!perm.isReadPermission) continue
            if (cleanedMap.values.any { it == uriStr }) continue
            val parsedUri = Uri.parse(uriStr)
            val docId = runCatching { DocumentsContract.getTreeDocumentId(parsedUri) }.getOrNull() ?: continue
            val key = "$MOUNTED_PREFIX$uriStr"
            val label = storedLabels[key] ?: deriveDefaultLabel(parsedUri, docId)
            valid[key] = SafGrant(
                key = key,
                treeUri = uriStr,
                rootDocumentId = docId,
                label = label,
                providerAuthority = parsedUri.authority.orEmpty(),
                isWritable = perm.isWritePermission,
            )
            cleanedMap[key] = uriStr
        }

        persistMap(cleanedMap)
        _grants.value = valid
    }

    fun recordTreeGrant(
        key: String,
        uri: Uri,
        customLabel: String? = null,
    ): SafGrant = synchronized(lock) {
        val rwFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val writeGranted = try {
            context.contentResolver.takePersistableUriPermission(uri, rwFlags)
            true
        } catch (_: SecurityException) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            false
        }

        val docId = DocumentsContract.getTreeDocumentId(uri)
        val uriStr = uri.toString()
        val label = customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: deriveDefaultLabel(uri, docId)

        val map = parseStoredMap().toMutableMap()
        map[key] = uriStr
        persistMap(map)

        val labels = parseStoredLabels().toMutableMap()
        labels[key] = label
        persistLabels(labels)

        val grant = SafGrant(
            key = key,
            treeUri = uriStr,
            rootDocumentId = docId,
            label = label,
            providerAuthority = uri.authority.orEmpty(),
            isWritable = writeGranted,
        )
        val updated = LinkedHashMap(_grants.value)
        updated[key] = grant
        _grants.value = updated
        grant
    }

    fun recordGenericMountedFolder(uri: Uri, customLabel: String? = null): SafGrant {
        val key = "$MOUNTED_PREFIX$uri"
        return recordTreeGrant(key, uri, customLabel)
    }

    fun revoke(key: String) = synchronized(lock) {
        val existing = _grants.value[key]
        if (existing != null) {
            val uri = Uri.parse(existing.treeUri)
            val otherUsesSameUri = _grants.value.any { (k, v) -> k != key && v.treeUri == existing.treeUri }
            if (!otherUsesSameUri) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
        }
        val map = parseStoredMap().toMutableMap()
        map.remove(key)
        persistMap(map)

        val labels = parseStoredLabels().toMutableMap()
        labels.remove(key)
        persistLabels(labels)

        val updated = LinkedHashMap(_grants.value)
        updated.remove(key)
        _grants.value = updated
    }

    fun getGrant(key: String): SafGrant? = _grants.value[key]

    fun findGrantByTreeUri(treeUri: String): SafGrant? =
        _grants.value.values.firstOrNull { it.treeUri == treeUri }

    private fun deriveDefaultLabel(uri: Uri, docId: String): String {
        val afterColon = docId.substringAfter(':', docId).trim('/')
        if (afterColon.isNotEmpty()) {
            return afterColon.substringAfterLast('/')
        }
        val prefix = docId.substringBefore(':', "")
        if (prefix.equals("primary", ignoreCase = true)) return "Internal folder"
        if (prefix.isNotEmpty()) return "Volume ($prefix)"
        return uri.lastPathSegment ?: "Mounted folder"
    }

    private fun parseStoredMap(): Map<String, String> {
        val raw = store.getString(KEY_SAF_TREES, null) ?: return emptyMap()
        val obj = runCatching { MiniJson.parse(raw) as? JsonValue.Obj }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj.entries) {
            (v as? JsonValue.Str)?.value?.let { out[k] = it }
        }
        return out
    }

    private fun persistMap(map: Map<String, String>) {
        val entries = LinkedHashMap<String, JsonValue>(map.size)
        for ((k, v) in map) entries[k] = JsonValue.Str(v)
        store.putString(KEY_SAF_TREES, MiniJson.stringify(JsonValue.Obj(entries)))
    }

    private fun parseStoredLabels(): Map<String, String> {
        val raw = store.getString(KEY_SAF_LABELS, null) ?: return emptyMap()
        val obj = runCatching { MiniJson.parse(raw) as? JsonValue.Obj }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj.entries) {
            (v as? JsonValue.Str)?.value?.let { out[k] = it }
        }
        return out
    }

    private fun persistLabels(map: Map<String, String>) {
        val entries = LinkedHashMap<String, JsonValue>(map.size)
        for ((k, v) in map) entries[k] = JsonValue.Str(v)
        store.putString(KEY_SAF_LABELS, MiniJson.stringify(JsonValue.Obj(entries)))
    }
}
