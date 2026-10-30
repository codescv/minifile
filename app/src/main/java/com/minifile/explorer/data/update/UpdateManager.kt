package com.minifile.explorer.data.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.update.AppUpdater
import com.minifile.explorer.core.update.CancelToken
import com.minifile.explorer.core.update.ReleaseAsset
import com.minifile.explorer.core.update.ReleaseJson
import com.minifile.explorer.core.update.SemanticVersion
import com.minifile.explorer.core.update.UpdateErrorCode
import com.minifile.explorer.core.update.UpdateException
import com.minifile.explorer.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val checkedAtMs: Long) : UpdateUiState
    data class Available(val release: ReleaseJson, val asset: ReleaseAsset) : UpdateUiState
    data class Downloading(
        val release: ReleaseJson,
        val asset: ReleaseAsset,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
    ) : UpdateUiState
    data class Verifying(val release: ReleaseJson) : UpdateUiState
    data class Ready(val release: ReleaseJson, val apkFile: File) : UpdateUiState
    data class Error(
        val code: UpdateErrorCode,
        val message: String,
        val releasePageUrl: String = DEFAULT_RELEASE_PAGE,
    ) : UpdateUiState

    companion object {
        const val DEFAULT_RELEASE_PAGE = "https://github.com/codescv/minifile/releases"
    }
}

sealed interface InstallLaunchResult {
    data object Launched : InstallLaunchResult
    data class NeedsUnknownSourcesPermission(val settingsIntent: Intent) : InstallLaunchResult
    data class Failed(val message: String) : InstallLaunchResult
}

class UpdateManager(
    context: Context,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours
        const val STALE_APK_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val apiBase = BuildConfig.UPDATE_API_BASE.trimEnd('/')
    private val allowLoopbackForDebug = BuildConfig.DEBUG && (
        apiBase.startsWith("http://127.0.0.1") || apiBase.startsWith("http://localhost")
        )

    private val updater = AppUpdater(
        latestUrl = "$apiBase/releases/latest",
        listUrl = "$apiBase/releases?per_page=5",
        allowInsecureLoopback = allowLoopbackForDebug,
    )

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _silentAvailablePrompt = MutableSharedFlow<ReleaseJson>(extraBufferCapacity = 1)
    val silentAvailablePrompt: SharedFlow<ReleaseJson> = _silentAvailablePrompt.asSharedFlow()

    private var activeCancelToken: CancelToken? = null
    private var activeJob: Job? = null
    private var pendingInstallAfterSettings = false
    private var startupCheckedThisProcess = false

    val updatesDir: File
        get() = File(appContext.cacheDir, "updates")

    fun checkOnStartupIfDue() {
        if (startupCheckedThisProcess) return
        startupCheckedThisProcess = true
        val currentSettings = settingsRepository.settings.value
        if (!currentSettings.updateAutoCheck) return
        val now = System.currentTimeMillis()
        if (now - currentSettings.updateLastCheckMs < AUTO_CHECK_INTERVAL_MS) return
        if (!isNetworkConnected()) return

        check(force = false)
    }

    fun check(force: Boolean) {
        if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading) return
        activeJob?.cancel()
        val token = CancelToken()
        activeCancelToken = token
        if (force) {
            _state.value = UpdateUiState.Checking
        }

        activeJob = scope.launch {
            withContext(Dispatchers.IO) {
                purgeStaleCachedApks()
            }
            try {
                val includePre = settingsRepository.settings.value.updateIncludePre
                val result = withContext(Dispatchers.IO) {
                    updater.checkLatestRelease(
                        currentVersion = BuildConfig.VERSION_NAME,
                        includePre = includePre,
                        cancel = token,
                    )
                }
                val now = System.currentTimeMillis()
                settingsRepository.update { it.copy(updateLastCheckMs = now) }

                if (result.updateAvailable) {
                    val asset = result.apk
                    if (asset == null) {
                        if (force) {
                            _state.value = UpdateUiState.Error(
                                code = UpdateErrorCode.NO_ASSET,
                                message = "Release ${result.latest.tagName} has no downloadable APK asset.",
                                releasePageUrl = result.latest.htmlUrl ?: UpdateUiState.DEFAULT_RELEASE_PAGE,
                            )
                        }
                    } else {
                        // Reuse already-verified cached APK if present
                        val cached = cachedApkFileForTag(result.latest.tagName)
                        if (cached.isFile && runCatching { ApkValidator.validate(appContext, cached) }.isSuccess) {
                            _state.value = UpdateUiState.Ready(result.latest, cached)
                        } else {
                            _state.value = UpdateUiState.Available(result.latest, asset)
                        }
                        if (!force && settingsRepository.settings.value.updateDismissedTag != result.latest.tagName) {
                            _silentAvailablePrompt.tryEmit(result.latest)
                        }
                    }
                } else {
                    _state.value = UpdateUiState.UpToDate(now)
                }
            } catch (e: UpdateException) {
                if (e.code == UpdateErrorCode.CANCELLED) {
                    _state.value = UpdateUiState.Idle
                } else if (force || _state.value !is UpdateUiState.Idle) {
                    _state.value = UpdateUiState.Error(
                        code = e.code,
                        message = friendlyErrorMessage(e),
                    )
                }
            } catch (e: Exception) {
                if (force) {
                    _state.value = UpdateUiState.Error(
                        code = UpdateErrorCode.NETWORK,
                        message = e.message ?: "Update check failed",
                    )
                }
            }
        }
    }

    fun dismissSilentNotice(tagName: String) {
        settingsRepository.update { it.copy(updateDismissedTag = tagName) }
    }

    fun download(onReadyToInstall: ((File) -> Unit)? = null) {
        val current = _state.value as? UpdateUiState.Available ?: return
        activeJob?.cancel()
        val token = CancelToken()
        activeCancelToken = token
        val release = current.release
        val asset = current.asset
        val dest = cachedApkFileForTag(release.tagName)

        activeJob = scope.launch {
            val startedMs = System.currentTimeMillis()
            _state.value = UpdateUiState.Downloading(
                release = release,
                asset = asset,
                downloadedBytes = 0L,
                totalBytes = asset.size,
                speedBytesPerSec = 0L,
            )
            try {
                val verifiedFile = withContext(Dispatchers.IO) {
                    updater.downloadApk(
                        url = asset.url,
                        dest = dest,
                        expectedSize = asset.size,
                        cancel = token,
                        validator = { tmpFile ->
                            scope.launch { _state.value = UpdateUiState.Verifying(release) }
                            ApkValidator.validate(appContext, tmpFile)
                        },
                        onProgress = { done, total ->
                            val elapsedSec = ((System.currentTimeMillis() - startedMs) / 1000.0).coerceAtLeast(0.1)
                            val bps = (done / elapsedSec).toLong()
                            _state.value = UpdateUiState.Downloading(
                                release = release,
                                asset = asset,
                                downloadedBytes = done,
                                totalBytes = total,
                                speedBytesPerSec = bps,
                            )
                        },
                    )
                }
                _state.value = UpdateUiState.Ready(release, verifiedFile)
                onReadyToInstall?.invoke(verifiedFile)
            } catch (e: UpdateException) {
                dest.delete()
                if (e.code == UpdateErrorCode.CANCELLED) {
                    _state.value = UpdateUiState.Available(release, asset)
                } else {
                    _state.value = UpdateUiState.Error(
                        code = e.code,
                        message = friendlyErrorMessage(e),
                        releasePageUrl = release.htmlUrl ?: UpdateUiState.DEFAULT_RELEASE_PAGE,
                    )
                }
            } catch (e: Exception) {
                dest.delete()
                _state.value = UpdateUiState.Error(
                    code = UpdateErrorCode.NETWORK,
                    message = e.message ?: "Download failed",
                    releasePageUrl = release.htmlUrl ?: UpdateUiState.DEFAULT_RELEASE_PAGE,
                )
            }
        }
    }

    fun cancel() {
        activeCancelToken?.cancel()
    }

    fun launchInstaller(context: Context): InstallLaunchResult {
        val ready = _state.value as? UpdateUiState.Ready
            ?: return InstallLaunchResult.Failed("No verified update ready to install.")
        if (!ready.apkFile.isFile) {
            _state.value = UpdateUiState.Idle
            return InstallLaunchResult.Failed("Cached update APK was removed; please check again.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallAfterSettings = true
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return InstallLaunchResult.NeedsUnknownSourcesPermission(settingsIntent)
        }

        pendingInstallAfterSettings = false
        return try {
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                ready.apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            InstallLaunchResult.Launched
        } catch (e: Exception) {
            InstallLaunchResult.Failed(e.message ?: "Could not launch system package installer.")
        }
    }

    fun onHostResume(context: Context) {
        if (pendingInstallAfterSettings) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                appContext.packageManager.canRequestPackageInstalls()
            ) {
                pendingInstallAfterSettings = false
                launchInstaller(context)
            }
        }
    }

    private fun cachedApkFileForTag(tagName: String): File {
        val clean = tagName.trim().removePrefix("v").replace(Regex("[^0-9A-Za-z._-]"), "_")
        updatesDir.mkdirs()
        return File(updatesDir, "minifile-$clean.apk")
    }

    private fun purgeStaleCachedApks() {
        val dir = updatesDir
        if (!dir.isDirectory) return
        val now = System.currentTimeMillis()
        val baseInstalled = SemanticVersion.stripDebugSuffix(BuildConfig.VERSION_NAME) ?: BuildConfig.VERSION_NAME
        dir.listFiles()?.forEach { f ->
            val tooOld = now - f.lastModified() > STALE_APK_MAX_AGE_MS
            val isTmp = f.name.endsWith(".tmp")
            val versionFromName = f.name.removePrefix("minifile-").removeSuffix(".apk")
            val notNewer = f.name.endsWith(".apk") && !SemanticVersion.isNewerVersion(baseInstalled, versionFromName)
            if (isTmp || tooOld || notNewer) {
                runCatching { f.delete() }
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun friendlyErrorMessage(e: UpdateException): String = when (e.code) {
        UpdateErrorCode.RATE_LIMITED -> "GitHub rate limit reached, try later."
        UpdateErrorCode.VERIFY_FAILED -> e.message ?: "The downloaded update is not signed by the same key as the installed app."
        UpdateErrorCode.INSECURE_URL -> "Blocked insecure update URL: ${e.message}"
        UpdateErrorCode.TIMEOUT -> "Connection to GitHub timed out."
        UpdateErrorCode.NOT_FOUND -> "No published releases found on GitHub yet."
        UpdateErrorCode.NO_ASSET -> "Release found, but no APK asset is attached."
        UpdateErrorCode.CORRUPT_DOWNLOAD -> "Downloaded APK was incomplete or corrupted (${e.message})."
        UpdateErrorCode.BAD_JSON -> "Could not parse GitHub release metadata."
        UpdateErrorCode.CANCELLED -> "Update cancelled."
        UpdateErrorCode.NETWORK -> e.message ?: "Could not reach GitHub Releases."
    }
}
