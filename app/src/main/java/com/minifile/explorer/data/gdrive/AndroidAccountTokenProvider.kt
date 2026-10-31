package com.minifile.explorer.data.gdrive

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.minifile.explorer.core.gdrive.DRIVE_SCOPE
import com.minifile.explorer.core.gdrive.GDriveAuthException
import com.minifile.explorer.core.gdrive.LockedTokenProvider
import com.minifile.explorer.core.gdrive.TokenState
import com.minifile.explorer.core.model.Clock
import java.io.IOException

/**
 * Android `AccountManager`-backed [LockedTokenProvider] for Mode 1 (`SYSTEM_ACCOUNT`) (§2.3, M1).
 *
 * `invalidateAuthToken` is invoked strictly inside [doRefresh] under [LockedTokenProvider]'s lock
 * AFTER the stale-token check, ensuring a coroutine that lost a 401 refresh race never invalidates
 * the newly issued token.
 */
class AndroidAccountTokenProvider(
    context: Context,
    private val accountName: String,
    private val clock: Clock = Clock.SYSTEM,
    initialToken: String? = null,
    initialExpiresAtMs: Long = 0L,
) : LockedTokenProvider(
    clock = clock,
    initialToken = initialToken,
    initialExpiresAtMs = initialExpiresAtMs,
) {
    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val AUTH_TOKEN_TYPE = "oauth2:$DRIVE_SCOPE"
        const val GMS_TOKEN_TTL_MS = 50L * 60_000L

        const val UNAVAILABLE_MESSAGE =
            "System sign-in isn't available for this build/device. Use Sign in with Browser or Paste Token."

        fun hasGoogleAccountAuthenticator(context: Context): Boolean =
            runCatching {
                AccountManager.get(context).authenticatorTypes.any { it.type == GOOGLE_ACCOUNT_TYPE }
            }.getOrDefault(false)

        @Suppress("DEPRECATION")
        fun newChooseGoogleAccountIntent(): Intent =
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf(GOOGLE_ACCOUNT_TYPE),
                null,
                null,
                null,
                null,
            )

        /**
         * Blocking call (must run off the main thread) that displays Google's consent sheet if needed
         * and returns an initial OAuth2 bearer token for [accountName].
         */
        fun requestInteractiveToken(activity: Activity, accountName: String): String {
            val account = Account(accountName, GOOGLE_ACCOUNT_TYPE)
            val bundle = AccountManager.get(activity).getAuthToken(
                account,
                AUTH_TOKEN_TYPE,
                Bundle(),
                activity,
                null,
                null,
            ).result
            val token = bundle?.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
            if (token != null) return token
            throw GDriveAuthException(UNAVAILABLE_MESSAGE, needsInteraction = true)
        }

        fun invalidateTokenBestEffort(context: Context, token: String?) {
            if (token.isNullOrBlank()) return
            runCatching {
                AccountManager.get(context.applicationContext)
                    .invalidateAuthToken(GOOGLE_ACCOUNT_TYPE, token)
            }
        }
    }

    private val accountManager: AccountManager = AccountManager.get(context.applicationContext)

    override fun doRefresh(stale: String?): TokenState {
        if (!stale.isNullOrBlank()) {
            runCatching { accountManager.invalidateAuthToken(GOOGLE_ACCOUNT_TYPE, stale) }
        }
        val account = Account(accountName, GOOGLE_ACCOUNT_TYPE)
        return try {
            val bundle = accountManager.getAuthToken(
                account,
                AUTH_TOKEN_TYPE,
                null,
                false,
                null,
                null,
            ).result
            if (bundle == null || bundle.containsKey(AccountManager.KEY_INTENT)) {
                throw GDriveAuthException(
                    message = "Google Drive sign-in expired — sign in again",
                    needsInteraction = true,
                )
            }
            val fresh = bundle.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }
                ?: throw GDriveAuthException(
                    message = "Google Drive sign-in expired — sign in again",
                    needsInteraction = true,
                )
            TokenState(token = fresh, expiresAtMs = clock.nowMs() + GMS_TOKEN_TTL_MS)
        } catch (e: GDriveAuthException) {
            throw e
        } catch (e: AuthenticatorException) {
            throw GDriveAuthException(
                message = "Google Drive sign-in expired — sign in again",
                needsInteraction = true,
                cause = e,
            )
        } catch (e: OperationCanceledException) {
            throw GDriveAuthException(
                message = "Google Drive sign-in cancelled",
                needsInteraction = true,
                cause = e,
            )
        } catch (e: IOException) {
            throw e
        }
    }
}
