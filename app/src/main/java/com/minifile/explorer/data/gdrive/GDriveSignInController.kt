package com.minifile.explorer.data.gdrive

import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.minifile.explorer.BuildConfig
import com.minifile.explorer.core.gdrive.CancellationIOException
import com.minifile.explorer.core.gdrive.GDriveAuthException
import com.minifile.explorer.core.gdrive.GDriveTokenParser
import com.minifile.explorer.core.gdrive.GoogleDriveFileSystem
import com.minifile.explorer.core.gdrive.GoogleOAuthLoopback
import com.minifile.explorer.core.gdrive.OAuthTokenProvider
import com.minifile.explorer.core.gdrive.fetchDriveUserEmail
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.model.GDriveServerBookmark
import com.minifile.explorer.core.vfs.PathUtils
import com.minifile.explorer.data.GDriveServersRepository
import com.minifile.explorer.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

sealed interface GDriveSignInState {
    data object Idle : GDriveSignInState
    data class WaitingForBrowser(val authUrl: String, val browserLaunchFailed: Boolean = false) : GDriveSignInState
    data object Exchanging : GDriveSignInState
    data class Success(val bookmark: GDriveServerBookmark, val isReauth: Boolean) : GDriveSignInState
    data class Error(val message: String, val authUrl: String? = null) : GDriveSignInState
}

/**
 * Coordinates Google Drive sign-in and re-authentication across all three auth modes (§2.3–§2.5).
 */
class GDriveSignInController(
    private val appContext: Context,
    private val serversRepository: GDriveServersRepository,
    private val settingsRepository: SettingsRepository,
    private val driveFileSystem: GoogleDriveFileSystem,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val _state = MutableStateFlow<GDriveSignInState>(GDriveSignInState.Idle)
    val state: StateFlow<GDriveSignInState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var activeLoopback: GoogleOAuthLoopback? = null

    fun reset() {
        cancelActiveWork()
        _state.value = GDriveSignInState.Idle
    }

    fun cancel() {
        cancelActiveWork()
        _state.value = GDriveSignInState.Idle
    }

    private fun cancelActiveWork() {
        runCatching { activeLoopback?.cancel() }
        activeLoopback = null
        activeJob?.cancel()
        activeJob = null
    }

    fun startBrowserSignIn(
        scope: CoroutineScope,
        clientId: String,
        clientSecret: String,
        displayName: String,
        initialPath: String,
        existingBookmark: GDriveServerBookmark? = null,
    ) {
        val effectiveClientId = clientId.trim().ifEmpty { BuildConfig.GDRIVE_DEFAULT_CLIENT_ID.trim() }
        val cleanSecret = clientSecret.trim()
        if (effectiveClientId.isEmpty()) {
            _state.value = GDriveSignInState.Error(
                "Enter a Google OAuth Desktop Client ID (or use Paste Token).",
            )
            return
        }

        // Persist user-entered client credentials for convenience across accounts (§1.3)
        if (clientId.trim().isNotEmpty() || cleanSecret.isNotEmpty()) {
            settingsRepository.update { s ->
                s.copy(
                    gdriveLastClientId = clientId.trim().ifEmpty { s.gdriveLastClientId },
                    gdriveLastClientSecret = cleanSecret,
                )
            }
        }

        cancelActiveWork()
        activeJob = scope.launch {
            var currentLoopback: GoogleOAuthLoopback? = null
            try {
                val bookmark = withContext(Dispatchers.IO) {
                    val loopback = GoogleOAuthLoopback(
                        clientId = effectiveClientId,
                        clientSecret = cleanSecret,
                        openBrowser = { url ->
                            val launched = launchSystemBrowser(url)
                            _state.value = GDriveSignInState.WaitingForBrowser(
                                authUrl = url,
                                browserLaunchFailed = !launched,
                            )
                        },
                        clock = clock,
                    )
                    currentLoopback = loopback
                    activeLoopback = loopback

                    val tokens = loopback.start()
                    _state.value = GDriveSignInState.Exchanging
                    val email = fetchDriveUserEmail(tokens.accessToken)
                    persistBookmark(
                        existing = existingBookmark,
                        displayName = displayName,
                        initialPath = initialPath,
                        email = email,
                        authMode = GDriveAuthMode.BROWSER_PKCE,
                        accountName = "",
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken,
                        expiresAtMs = tokens.expiresAtMs,
                        clientId = effectiveClientId,
                        clientSecret = cleanSecret,
                    )
                }
                _state.value = GDriveSignInState.Success(
                    bookmark = bookmark,
                    isReauth = existingBookmark != null,
                )
            } catch (_: CancellationIOException) {
                // Cancelled via cancel() / dialog dismiss
            } catch (_: CancellationException) {
                // Coroutine cancelled
            } catch (e: Exception) {
                _state.value = GDriveSignInState.Error(
                    message = e.message ?: "Google browser sign-in failed",
                    authUrl = currentLoopback?.authUrl,
                )
            } finally {
                if (activeLoopback === currentLoopback) {
                    activeLoopback = null
                }
            }
        }
    }

    fun submitPastedCredentials(
        scope: CoroutineScope,
        rawText: String,
        overrideClientId: String,
        overrideClientSecret: String,
        displayName: String,
        initialPath: String,
        existingBookmark: GDriveServerBookmark? = null,
    ) {
        val parsed = GDriveTokenParser.parse(rawText, clock = clock)
        if (parsed == null) {
            _state.value = GDriveSignInState.Error(
                "Invalid token input. Paste JSON from 'minifile gdrive-auth' / 'rclone authorize \"drive\"', an rclone.conf block, or a bare access token (≥ 20 chars).",
            )
            return
        }

        val effectiveClientId = overrideClientId.trim().ifEmpty { parsed.clientId }
        val effectiveClientSecret = overrideClientSecret.trim().ifEmpty { parsed.clientSecret }
        if (overrideClientId.trim().isNotEmpty() || overrideClientSecret.trim().isNotEmpty()) {
            settingsRepository.update { s ->
                s.copy(
                    gdriveLastClientId = overrideClientId.trim().ifEmpty { s.gdriveLastClientId },
                    gdriveLastClientSecret = overrideClientSecret.ifEmpty { s.gdriveLastClientSecret },
                )
            }
        }

        cancelActiveWork()
        _state.value = GDriveSignInState.Exchanging
        activeJob = scope.launch {
            try {
                val bookmark = withContext(Dispatchers.IO) {
                    var activeAccess = parsed.accessToken
                    var activeRefresh = parsed.refreshToken
                    var activeExpiresAt = parsed.expiresAtMs

                    val canRefresh = activeRefresh.isNotBlank() && effectiveClientId.isNotBlank()
                    if (canRefresh && activeExpiresAt in 1L..clock.nowMs()) {
                        val tempProvider = OAuthTokenProvider(
                            clientId = effectiveClientId,
                            clientSecret = effectiveClientSecret,
                            initialAccessToken = activeAccess,
                            initialRefreshToken = activeRefresh,
                            initialExpiresAtMs = 0L,
                            clock = clock,
                            onTokensRefreshed = { acc, ref, exp ->
                                activeAccess = acc
                                if (!ref.isNullOrBlank()) activeRefresh = ref
                                activeExpiresAt = exp
                            },
                        )
                        activeAccess = tempProvider.accessToken()
                    }

                    val email = try {
                        fetchDriveUserEmail(activeAccess)
                    } catch (authErr: GDriveAuthException) {
                        if (!canRefresh) throw authErr
                        val tempProvider = OAuthTokenProvider(
                            clientId = effectiveClientId,
                            clientSecret = effectiveClientSecret,
                            initialAccessToken = activeAccess,
                            initialRefreshToken = activeRefresh,
                            initialExpiresAtMs = 0L,
                            clock = clock,
                            onTokensRefreshed = { acc, ref, exp ->
                                activeAccess = acc
                                if (!ref.isNullOrBlank()) activeRefresh = ref
                                activeExpiresAt = exp
                            },
                        )
                        activeAccess = tempProvider.accessToken(staleToken = activeAccess)
                        fetchDriveUserEmail(activeAccess)
                    }

                    persistBookmark(
                        existing = existingBookmark,
                        displayName = displayName,
                        initialPath = initialPath,
                        email = email,
                        authMode = GDriveAuthMode.PASTED_TOKEN,
                        accountName = "",
                        accessToken = activeAccess,
                        refreshToken = activeRefresh,
                        expiresAtMs = activeExpiresAt,
                        clientId = effectiveClientId,
                        clientSecret = effectiveClientSecret,
                    )
                }
                _state.value = GDriveSignInState.Success(
                    bookmark = bookmark,
                    isReauth = existingBookmark != null,
                )
            } catch (_: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                _state.value = GDriveSignInState.Error(
                    message = e.message ?: "Could not verify pasted Google Drive token",
                )
            }
        }
    }

    fun completeSystemAccountSignIn(
        scope: CoroutineScope,
        activity: Activity,
        accountName: String,
        displayName: String,
        initialPath: String,
        existingBookmark: GDriveServerBookmark? = null,
    ) {
        val cleanAccount = accountName.trim()
        if (cleanAccount.isEmpty()) {
            _state.value = GDriveSignInState.Error("No Google account selected.")
            return
        }

        cancelActiveWork()
        _state.value = GDriveSignInState.Exchanging
        activeJob = scope.launch {
            try {
                val bookmark = withContext(Dispatchers.IO) {
                    val token = try {
                        AndroidAccountTokenProvider.requestInteractiveToken(activity, cleanAccount)
                    } catch (_: OperationCanceledException) {
                        throw CancellationIOException("Google account authorization was cancelled.")
                    } catch (_: AuthenticatorException) {
                        throw GDriveAuthException(AndroidAccountTokenProvider.UNAVAILABLE_MESSAGE, needsInteraction = true)
                    } catch (_: IOException) {
                        throw GDriveAuthException(AndroidAccountTokenProvider.UNAVAILABLE_MESSAGE, needsInteraction = true)
                    }

                    val email = runCatching { fetchDriveUserEmail(token) }
                        .getOrDefault("")
                        .ifBlank { cleanAccount }

                    persistBookmark(
                        existing = existingBookmark,
                        displayName = displayName,
                        initialPath = initialPath,
                        email = email,
                        authMode = GDriveAuthMode.SYSTEM_ACCOUNT,
                        accountName = cleanAccount,
                        accessToken = "",
                        refreshToken = "",
                        expiresAtMs = 0L,
                        clientId = "",
                        clientSecret = "",
                    )
                }
                _state.value = GDriveSignInState.Success(
                    bookmark = bookmark,
                    isReauth = existingBookmark != null,
                )
            } catch (cancelErr: CancellationIOException) {
                _state.value = GDriveSignInState.Error(cancelErr.message ?: "Sign-in was cancelled.")
            } catch (_: CancellationException) {
                // Cancelled
            } catch (e: GDriveAuthException) {
                _state.value = GDriveSignInState.Error(
                    e.message ?: AndroidAccountTokenProvider.UNAVAILABLE_MESSAGE,
                )
            } catch (_: Exception) {
                _state.value = GDriveSignInState.Error(AndroidAccountTokenProvider.UNAVAILABLE_MESSAGE)
            }
        }
    }

    private suspend fun persistBookmark(
        existing: GDriveServerBookmark?,
        displayName: String,
        initialPath: String,
        email: String,
        authMode: GDriveAuthMode,
        accountName: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMs: Long,
        clientId: String,
        clientSecret: String,
    ): GDriveServerBookmark {
        val cleanPath = PathUtils.normalizePosixPath(initialPath.trim().ifEmpty { "/" })
        val fallbackName = email.ifBlank { accountName.ifBlank { "Google Drive" } }
        val cleanName = displayName.trim().ifEmpty { existing?.name?.takeIf { it.isNotBlank() } ?: fallbackName }
        val target = GDriveServerBookmark(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = cleanName,
            email = email,
            authMode = authMode,
            accountName = accountName,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAtMs,
            clientId = clientId,
            clientSecret = clientSecret,
            initialPath = cleanPath,
            createdAt = existing?.createdAt?.takeIf { it > 0L } ?: clock.nowMs(),
        )
        return if (existing != null) {
            serversRepository.update(target)
            driveFileSystem.invalidateServer(target.id)
            serversRepository.findById(target.id) ?: target
        } else {
            val added = serversRepository.add(target)
            driveFileSystem.invalidateServer(added.id)
            added
        }
    }

    private fun launchSystemBrowser(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
