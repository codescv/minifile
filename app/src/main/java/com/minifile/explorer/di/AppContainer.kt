package com.minifile.explorer.di

import android.content.Context
import android.os.Build
import android.os.Environment
import com.minifile.explorer.core.ftp.FtpClientFileSystem
import com.minifile.explorer.core.gdrive.GoogleDriveFileSystem
import com.minifile.explorer.core.gdrive.OAuthTokenProvider
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.ops.ChecksumCalculator
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.NomediaManager
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.data.AndroidMediaScanNotifier
import com.minifile.explorer.data.AndroidVolumeProvider
import com.minifile.explorer.data.DefaultFavoritesRepository
import com.minifile.explorer.data.DefaultFtpServersRepository
import com.minifile.explorer.data.DefaultGDriveServersRepository
import com.minifile.explorer.data.FavoritesRepository
import com.minifile.explorer.data.FtpServersRepository
import com.minifile.explorer.data.GDriveServersRepository
import com.minifile.explorer.data.LanAddressResolver
import com.minifile.explorer.data.PersistedTreeStore
import com.minifile.explorer.data.SafFileSystem
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.data.SharedPrefsKeyValueStore
import com.minifile.explorer.data.gdrive.AndroidAccountTokenProvider
import com.minifile.explorer.data.gdrive.GDriveSignInController
import com.minifile.explorer.data.update.UpdateManager
import com.minifile.explorer.service.OperationRegistry
import com.minifile.explorer.service.PeerDiscoveryHolder
import com.minifile.explorer.service.PeerReceiveBridge

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val keyValueStore = SharedPrefsKeyValueStore(appContext)
    val settingsRepository = SettingsRepository(
        store = keyValueStore,
        defaultDeviceNameProvider = { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android" },
    )
    val persistedTreeStore = PersistedTreeStore(appContext, keyValueStore)
    val ftpServersRepository: FtpServersRepository = DefaultFtpServersRepository(
        store = keyValueStore,
        clock = Clock.SYSTEM,
    )
    val gdriveServersRepository: GDriveServersRepository = DefaultGDriveServersRepository(
        store = keyValueStore,
        clock = Clock.SYSTEM,
    )

    val localFileSystem = LocalFileSystem()
    val safFileSystem = SafFileSystem(appContext)
    val ftpClientFileSystem = FtpClientFileSystem(
        serverLookup = { id -> ftpServersRepository.findById(id) },
    )
    val googleDriveFileSystem = GoogleDriveFileSystem(
        serverLookup = { id -> gdriveServersRepository.findById(id) },
        tokenProviderFor = { bookmark ->
            when (bookmark.authMode) {
                GDriveAuthMode.SYSTEM_ACCOUNT -> AndroidAccountTokenProvider(
                    context = appContext,
                    accountName = bookmark.accountName,
                    clock = Clock.SYSTEM,
                )

                GDriveAuthMode.BROWSER_PKCE,
                GDriveAuthMode.PASTED_TOKEN -> OAuthTokenProvider(
                    clientId = bookmark.clientId,
                    clientSecret = bookmark.clientSecret,
                    initialAccessToken = bookmark.accessToken,
                    initialRefreshToken = bookmark.refreshToken,
                    initialExpiresAtMs = bookmark.expiresAtMs,
                    clock = Clock.SYSTEM,
                    onTokensRefreshed = { accessToken, refreshToken, expiresAtMs ->
                        gdriveServersRepository.updateTokens(
                            id = bookmark.id,
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expiresAtMs = expiresAtMs,
                        )
                    },
                )
            }
        },
        clock = Clock.SYSTEM,
    )
    val gdriveSignInController = GDriveSignInController(
        appContext = appContext,
        serversRepository = gdriveServersRepository,
        settingsRepository = settingsRepository,
        driveFileSystem = googleDriveFileSystem,
        clock = Clock.SYSTEM,
    )
    val fileSystem = FileSystemRepository(
        listOf(localFileSystem, safFileSystem, ftpClientFileSystem, googleDriveFileSystem),
    )

    val favoritesRepository: FavoritesRepository = DefaultFavoritesRepository(
        store = keyValueStore,
        clock = Clock.SYSTEM,
        defaultPrimaryRoot = Environment.getExternalStorageDirectory().absolutePath,
    )

    val volumeProvider = AndroidVolumeProvider(appContext, persistedTreeStore)
    val mediaScanNotifier = AndroidMediaScanNotifier(appContext)
    val nomediaManager = NomediaManager(fileSystem, mediaScanNotifier)
    val fileOperations = FileOperations(fileSystem, Clock.SYSTEM)
    val zipArchiver = ZipArchiver(fileSystem)
    val checksumCalculator = ChecksumCalculator(fileSystem)
    val lanAddressResolver = LanAddressResolver(appContext)
    val updateManager = UpdateManager(appContext, settingsRepository)
    val operationRegistry = OperationRegistry.instance
    val peerDiscoveryHolder = PeerDiscoveryHolder(
        context = appContext,
        settingsRepository = settingsRepository,
        lanAddressResolver = lanAddressResolver,
    )
    val peerReceiveBridge = PeerReceiveBridge(
        context = appContext,
        operationRegistry = operationRegistry,
        fs = fileSystem,
    )
}
