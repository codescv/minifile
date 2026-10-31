package com.minifile.explorer.data

import com.minifile.explorer.core.model.AppSettings
import com.minifile.explorer.core.model.JsonValue
import com.minifile.explorer.core.model.KeyValueStore
import com.minifile.explorer.core.model.MiniJson
import com.minifile.explorer.core.model.SortSpec
import com.minifile.explorer.core.model.ThemeMode
import com.minifile.explorer.core.model.ViewMode
import com.minifile.explorer.core.peer.PeerPathPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SettingsRepository(
    private val store: KeyValueStore,
    private val defaultDeviceNameProvider: () -> String = { "Files" },
) {
    companion object {
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_SORT = "sort"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_SHOW_THUMBNAILS = "show_thumbnails"
        const val KEY_CONFIRM_OVERWRITE = "confirm_overwrite"
        const val KEY_OPEN_TEXT_BUILT_IN = "open_text_in_editor"
        const val KEY_EDITOR_FONT_SP = "editor_font_sp"
        const val KEY_EDITOR_WRAP = "editor_wrap"
        const val KEY_EDITOR_LINE_NUMBERS = "editor_line_numbers"
        const val KEY_REMEMBER_LAST_FOLDER = "remember_last_folder"
        const val KEY_LAST_FOLDER = "last_folder"
        const val KEY_ONBOARDING_DONE = "onboarding_done"

        const val KEY_HTTP_PORT = "http_port"
        const val KEY_HTTP_ROOT = "http_root"
        const val KEY_HTTP_READ_ONLY = "http_read_only"

        const val KEY_FTP_PORT = "ftp_port"
        const val KEY_FTP_ROOT = "ftp_root"
        const val KEY_FTP_AUTH_MODE = "ftp_auth_mode"
        const val KEY_FTP_USER = "ftp_user"
        const val KEY_FTP_PASSWORD = "ftp_password"
        const val KEY_FTP_READ_ONLY = "ftp_read_only"
        const val KEY_FTP_PASV_RANGE = "ftp_pasv_range"
        const val KEY_FTP_IDLE_TIMEOUT = "ftp_idle_timeout"
        const val KEY_FTP_KEEP_SCREEN_ON = "ftp_keep_screen_on"

        const val KEY_FAV_PICKER_RECENCY = "fav_picker_recency"
        const val KEY_PEER_DEVICE_ID = "peer_device_id"
        const val KEY_PEER_DEVICE_NAME = "peer_device_name"

        const val KEY_UPDATE_AUTO_CHECK = "update_auto_check"
        const val KEY_UPDATE_INCLUDE_PRE = "update_include_pre"
        const val KEY_UPDATE_LAST_CHECK_MS = "update_last_check_ms"
        const val KEY_UPDATE_DISMISSED_TAG = "update_dismissed_tag"

        const val KEY_GDRIVE_LAST_CLIENT_ID = "gdrive_last_client_id"
        const val KEY_GDRIVE_LAST_CLIENT_SECRET = "gdrive_last_client_secret"
    }

    private data class DebugFtpOverrides(
        val port: Int? = null,
        val pasvStart: Int? = null,
        val pasvEnd: Int? = null,
    )

    private val lock = Any()
    private var debugOverrides = DebugFtpOverrides()
    private var persisted: AppSettings = loadFromStore()
    private val _settings = MutableStateFlow(applyOverrides(persisted))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadFromStore(): AppSettings {
        val sortJson = store.getString(KEY_SORT, null)
        val sortSpec = sortJson?.let {
            runCatching { MiniJson.parse(it) as? JsonValue.Obj }.getOrNull()?.let(SortSpec::fromJson)
        } ?: SortSpec()

        val viewMode = store.getString(KEY_VIEW_MODE, null)?.let { name ->
            ViewMode.entries.firstOrNull { it.name == name }
        } ?: ViewMode.LIST

        val themeMode = store.getString(KEY_THEME, null)?.let { name ->
            ThemeMode.entries.firstOrNull { it.name == name }
        } ?: ThemeMode.SYSTEM

        val pasvRaw = store.getString(KEY_FTP_PASV_RANGE, "50000-50100") ?: "50000-50100"
        val pasvParts = pasvRaw.split('-')
        val pasvStart = pasvParts.getOrNull(0)?.trim()?.toIntOrNull() ?: 50000
        val pasvEnd = pasvParts.getOrNull(1)?.trim()?.toIntOrNull() ?: 50100

        val authMode = store.getString(KEY_FTP_AUTH_MODE, "anonymous") ?: "anonymous"

        val persistedPeerId = store.getString(KEY_PEER_DEVICE_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { generated ->
                store.putString(KEY_PEER_DEVICE_ID, generated)
            }
        val storedPeerName = store.getString(KEY_PEER_DEVICE_NAME, null)
        val peerName = if (storedPeerName != null) {
            PeerPathPolicy.sanitizeDisplayName(storedPeerName, 64).ifEmpty { "Files" }
        } else {
            PeerPathPolicy.sanitizeDisplayName(defaultDeviceNameProvider(), 64)
                .ifEmpty { "Files" }
                .also { initialName -> store.putString(KEY_PEER_DEVICE_NAME, initialName) }
        }

        return AppSettings(
            showHidden = store.getBoolean(KEY_SHOW_HIDDEN, false),
            viewMode = viewMode,
            sort = sortSpec,
            themeMode = themeMode,
            dynamicColor = store.getBoolean(KEY_DYNAMIC_COLOR, true),
            showThumbnails = store.getBoolean(KEY_SHOW_THUMBNAILS, true),
            confirmOverwrite = store.getBoolean(KEY_CONFIRM_OVERWRITE, true),
            openTextInBuiltInEditor = store.getBoolean(KEY_OPEN_TEXT_BUILT_IN, true),
            editorFontSp = store.getInt(KEY_EDITOR_FONT_SP, 14).coerceIn(10, 32),
            editorWrap = store.getBoolean(KEY_EDITOR_WRAP, false),
            editorLineNumbers = store.getBoolean(KEY_EDITOR_LINE_NUMBERS, true),
            rememberLastFolder = store.getBoolean(KEY_REMEMBER_LAST_FOLDER, false),
            lastFolder = store.getString(KEY_LAST_FOLDER, null),
            onboardingDone = store.getBoolean(KEY_ONBOARDING_DONE, false),
            httpPort = store.getInt(KEY_HTTP_PORT, 8080).coerceIn(1024, 65535),
            httpRoot = store.getString(KEY_HTTP_ROOT, null),
            httpReadOnly = store.getBoolean(KEY_HTTP_READ_ONLY, false),
            ftpPort = store.getInt(KEY_FTP_PORT, 2121).coerceIn(1024, 65535),
            ftpRoot = store.getString(KEY_FTP_ROOT, null),
            ftpAnonymous = authMode != "credentials",
            ftpUsername = store.getString(KEY_FTP_USER, "minifile") ?: "minifile",
            ftpPassword = store.getString(KEY_FTP_PASSWORD, "") ?: "",
            ftpReadOnly = store.getBoolean(KEY_FTP_READ_ONLY, false),
            ftpPasvStart = pasvStart.coerceIn(1024, 65535),
            ftpPasvEnd = pasvEnd.coerceIn(pasvStart.coerceIn(1024, 65535), 65535),
            ftpIdleTimeoutSec = store.getInt(KEY_FTP_IDLE_TIMEOUT, 300).coerceIn(15, 7200),
            ftpKeepScreenOn = store.getBoolean(KEY_FTP_KEEP_SCREEN_ON, true),
            favoritePickerSortByRecency = store.getBoolean(KEY_FAV_PICKER_RECENCY, true),
            peerDeviceId = persistedPeerId,
            peerDeviceName = peerName,
            updateAutoCheck = store.getBoolean(KEY_UPDATE_AUTO_CHECK, true),
            updateIncludePre = store.getBoolean(KEY_UPDATE_INCLUDE_PRE, false),
            updateLastCheckMs = store.getLong(KEY_UPDATE_LAST_CHECK_MS, 0L),
            updateDismissedTag = store.getString(KEY_UPDATE_DISMISSED_TAG, null),
            gdriveLastClientId = store.getString(KEY_GDRIVE_LAST_CLIENT_ID, "") ?: "",
            gdriveLastClientSecret = store.getString(KEY_GDRIVE_LAST_CLIENT_SECRET, "") ?: "",
        )
    }

    private fun applyOverrides(base: AppSettings): AppSettings {
        val ov = debugOverrides
        val pStart = ov.pasvStart ?: base.ftpPasvStart
        val pEnd = (ov.pasvEnd ?: base.ftpPasvEnd).coerceAtLeast(pStart)
        return base.copy(
            ftpPort = ov.port ?: base.ftpPort,
            ftpPasvStart = pStart,
            ftpPasvEnd = pEnd,
        )
    }

    /**
     * Process-scoped debug override used by ACID E2E (`ARCHITECTURE.md` §6.2 / §12.3 E13).
     * Never writes to SharedPreferences.
     */
    fun setDebugFtpOverrides(port: Int?, pasvMin: Int?, pasvMax: Int?) = synchronized(lock) {
        debugOverrides = DebugFtpOverrides(
            port = port?.takeIf { it in 1024..65535 } ?: debugOverrides.port,
            pasvStart = pasvMin?.takeIf { it in 1024..65535 } ?: debugOverrides.pasvStart,
            pasvEnd = pasvMax?.takeIf { it in 1024..65535 } ?: debugOverrides.pasvEnd,
        )
        _settings.value = applyOverrides(persisted)
    }

    fun update(transform: (AppSettings) -> AppSettings) = synchronized(lock) {
        val prevPersisted = persisted
        val next = transform(prevPersisted)
        persist(next)
        persisted = next
        // Only clear a debug override if that specific persisted field was explicitly modified
        if (next.ftpPort != prevPersisted.ftpPort) {
            debugOverrides = debugOverrides.copy(port = null)
        }
        if (next.ftpPasvStart != prevPersisted.ftpPasvStart ||
            next.ftpPasvEnd != prevPersisted.ftpPasvEnd
        ) {
            debugOverrides = debugOverrides.copy(pasvStart = null, pasvEnd = null)
        }
        _settings.value = applyOverrides(next)
    }

    fun setTransferRoot(serialized: String?) {
        update { it.copy(ftpRoot = serialized, httpRoot = serialized) }
    }

    fun setTransferReadOnly(ro: Boolean) {
        update { it.copy(ftpReadOnly = ro, httpReadOnly = ro) }
    }

    fun setPeerDeviceName(raw: String) {
        val clean = PeerPathPolicy.sanitizeDisplayName(raw, 64)
            .ifEmpty { PeerPathPolicy.sanitizeDisplayName(defaultDeviceNameProvider(), 64).ifEmpty { "Files" } }
        update { it.copy(peerDeviceName = clean) }
    }

    private fun persist(s: AppSettings) {
        store.putBoolean(KEY_SHOW_HIDDEN, s.showHidden)
        store.putString(KEY_VIEW_MODE, s.viewMode.name)
        store.putString(KEY_SORT, MiniJson.stringify(s.sort.toJson()))
        store.putString(KEY_THEME, s.themeMode.name)
        store.putBoolean(KEY_DYNAMIC_COLOR, s.dynamicColor)
        store.putBoolean(KEY_SHOW_THUMBNAILS, s.showThumbnails)
        store.putBoolean(KEY_CONFIRM_OVERWRITE, s.confirmOverwrite)
        store.putBoolean(KEY_OPEN_TEXT_BUILT_IN, s.openTextInBuiltInEditor)
        store.putInt(KEY_EDITOR_FONT_SP, s.editorFontSp)
        store.putBoolean(KEY_EDITOR_WRAP, s.editorWrap)
        store.putBoolean(KEY_EDITOR_LINE_NUMBERS, s.editorLineNumbers)
        store.putBoolean(KEY_REMEMBER_LAST_FOLDER, s.rememberLastFolder)
        store.putString(KEY_LAST_FOLDER, s.lastFolder)
        store.putBoolean(KEY_ONBOARDING_DONE, s.onboardingDone)

        store.putInt(KEY_HTTP_PORT, s.httpPort)
        store.putString(KEY_HTTP_ROOT, s.httpRoot)
        store.putBoolean(KEY_HTTP_READ_ONLY, s.httpReadOnly)

        store.putInt(KEY_FTP_PORT, s.ftpPort)
        store.putString(KEY_FTP_ROOT, s.ftpRoot)
        store.putString(KEY_FTP_AUTH_MODE, if (s.ftpAnonymous) "anonymous" else "credentials")
        store.putString(KEY_FTP_USER, s.ftpUsername)
        store.putString(KEY_FTP_PASSWORD, s.ftpPassword)
        store.putBoolean(KEY_FTP_READ_ONLY, s.ftpReadOnly)
        store.putString(KEY_FTP_PASV_RANGE, "${s.ftpPasvStart}-${s.ftpPasvEnd}")
        store.putInt(KEY_FTP_IDLE_TIMEOUT, s.ftpIdleTimeoutSec)
        store.putBoolean(KEY_FTP_KEEP_SCREEN_ON, s.ftpKeepScreenOn)

        store.putBoolean(KEY_FAV_PICKER_RECENCY, s.favoritePickerSortByRecency)
        if (s.peerDeviceId.isNotBlank()) {
            store.putString(KEY_PEER_DEVICE_ID, s.peerDeviceId)
        }
        store.putString(
            KEY_PEER_DEVICE_NAME,
            PeerPathPolicy.sanitizeDisplayName(s.peerDeviceName, 64).ifEmpty { "Files" },
        )

        store.putBoolean(KEY_UPDATE_AUTO_CHECK, s.updateAutoCheck)
        store.putBoolean(KEY_UPDATE_INCLUDE_PRE, s.updateIncludePre)
        store.putLong(KEY_UPDATE_LAST_CHECK_MS, s.updateLastCheckMs)
        store.putString(KEY_UPDATE_DISMISSED_TAG, s.updateDismissedTag)

        store.putString(KEY_GDRIVE_LAST_CLIENT_ID, s.gdriveLastClientId)
        store.putString(KEY_GDRIVE_LAST_CLIENT_SECRET, s.gdriveLastClientSecret)
    }
}
