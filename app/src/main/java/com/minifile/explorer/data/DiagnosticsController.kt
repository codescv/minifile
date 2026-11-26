package com.minifile.explorer.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.diag.Diag
import com.minifile.explorer.core.diag.DiagnosticEnvironment
import com.minifile.explorer.core.diag.DiagnosticExport
import com.minifile.explorer.core.diag.DiagnosticLogStats
import com.minifile.explorer.core.diag.DiagnosticLogger
import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.Str
import com.minifile.explorer.service.FtpServerHolder
import com.minifile.explorer.service.HttpServerHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.ZoneId

/**
 * Android glue for Settings ▸ Diagnostics: owns the process-wide [DiagnosticLogger] (`filesDir/diag/`), keeps it in
 * sync with [com.minifile.explorer.core.model.AppSettings.diagnosticLoggingEnabled], builds the app/device/network
 * snapshot for the `[SESSION]` banner + export header, and produces the clipboard text / share-sheet file.
 */
class DiagnosticsController(
    context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val logger = DiagnosticLogger(File(appContext.filesDir, LOG_DIR))
    val stats: StateFlow<DiagnosticLogStats> = logger.stats

    /** Installs the logger and applies the persisted toggle (also on every later change, from any writer). */
    fun start() {
        Diag.install(logger)
        scope.launch {
            var first = true
            settingsRepository.settings
                .map { it.diagnosticLoggingEnabled }
                .distinctUntilChanged()
                .collect { on ->
                    apply(on, if (first) "process start (diagnostic logging on)" else "diagnostic logging enabled")
                    first = false
                }
        }
    }

    /** Settings toggle: persists and applies immediately (the settings collector then sees no change). */
    fun setEnabled(on: Boolean) {
        settingsRepository.setDiagnosticLoggingEnabled(on)
        scope.launch { apply(on, "diagnostic logging enabled") }
    }

    private fun apply(on: Boolean, reason: String) {
        logger.setEnabled(on) { DiagnosticExport.sessionBanner(environment(), reason) }
    }

    /** Deletes both log files; when still recording, a fresh `[SESSION]` banner starts the new log. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        logger.clear()
        if (logger.enabled) logger.log(Diag.SESSION, DiagnosticExport.sessionBanner(environment(), "log cleared"))
        File(appContext.cacheDir, DiagnosticExport.EXPORT_DIR).listFiles()?.forEach { runCatching { it.delete() } }
    }

    /** Header + chronological `.1` + current log; [maxChars] keeps the newest lines (clipboard / Binder safety). */
    suspend fun exportText(maxChars: Int? = null): String = withContext(Dispatchers.IO) {
        logger.flush()
        val body = logger.readAll()
        val header = DiagnosticExport.header(environment(), System.currentTimeMillis(), ZoneId.systemDefault(), logger.stats.value)
        DiagnosticExport.compose(header, body, maxChars)
    }

    /** Clipboard copy: capped at [DiagnosticExport.CLIPBOARD_MAX_CHARS] so `TransactionTooLargeException` can't happen. */
    suspend fun clipboardText(): String = exportText(DiagnosticExport.CLIPBOARD_MAX_CHARS)

    /** Writes `cacheDir/diag_export/minifile-android-diag-<stamp>.txt` (previous exports are removed first). */
    suspend fun writeExportFile(): File = withContext(Dispatchers.IO) {
        val text = exportText(null)
        val dir = File(appContext.cacheDir, DiagnosticExport.EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val file = File(dir, DiagnosticExport.exportFileName(System.currentTimeMillis(), ZoneId.systemDefault()))
        file.writeText(text, Charsets.UTF_8)
        file
    }

    /** `ACTION_SEND` chooser for [file] with a read grant on the `FileProvider` URI (also mirrored in `ClipData`). */
    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newUri(appContext.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, AppI18n.current()[Str.DIAG_SHARE_CHOOSER]).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun environment(): DiagnosticEnvironment {
        val s = settingsRepository.settings.value
        val http = HttpServerHolder.state.value
        val ftp = FtpServerHolder.state.value
        val gate = HttpServerHolder.hotspotGate
        return DiagnosticEnvironment(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            platform = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            interfaces = interfaceSummary(),
            discoverable = s.peerDiscoverable,
            httpServer = if (http.isRunning) "running :${http.boundPort}" else if (http.isStarting) "starting" else "stopped",
            ftpServer = if (ftp.isRunning) "running :${ftp.boundPort}" else "stopped",
            extra = listOf(
                "zone" to ZoneId.systemDefault().id,
                "gate" to "bind=${gate.hotspotBindIp?.hostAddress ?: "-"} announce=${gate.announceMode} " +
                    "sessionArmed=${gate.isSessionArmed} confine=${gate.confineToHotspot}",
                "requireApproval" to s.peerConfirmReceive.toString(),
            ),
        )
    }

    private fun interfaceSummary(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.interfaceAddresses.orEmpty()
                    .filter { it.address is Inet4Address }
                    .map { ia ->
                        val tag = if (LanAddressResolver.isHotspotInterfaceName(nif.name)) " (hotspot-capable)" else ""
                        "${nif.name} ${ia.address.hostAddress}/${ia.networkPrefixLength}$tag"
                    }
            }
            .sorted()
    }.getOrDefault(emptyList())

    companion object {
        const val LOG_DIR = "diag"
    }
}
