package com.minifile.explorer.di

import android.content.Context
import android.os.Build
import android.os.Environment
import com.minifile.explorer.core.ftp.FtpClientFileSystem
import com.minifile.explorer.core.gallery.GalleryRepository
import com.minifile.explorer.core.gallery.VfsListingIndexSource
import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.gdrive.GoogleDriveFileSystem
import com.minifile.explorer.core.gdrive.OAuthTokenProvider
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.model.GDriveAuthMode
import com.minifile.explorer.core.ops.ChecksumCalculator
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.NomediaManager
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.share.ShareImportEngine
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.core.vfs.VfsPath
import com.minifile.explorer.data.AndroidMediaScanNotifier
import com.minifile.explorer.data.DiagnosticsController
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
import com.minifile.explorer.data.gallery.ExifGeoReader
import com.minifile.explorer.data.gallery.GalleryThumbnailCache
import com.minifile.explorer.data.gallery.GeoIndexStore
import com.minifile.explorer.data.gallery.MediaStoreIndexSource
import com.minifile.explorer.data.gallery.MediaStoreObserver
import com.minifile.explorer.data.gdrive.AndroidAccountTokenProvider
import com.minifile.explorer.data.gdrive.GDriveSignInController
import com.minifile.explorer.data.media.ImageEditorEngine
import com.minifile.explorer.data.media.LosslessMp4Remuxer
import com.minifile.explorer.data.media.VideoTranscoder
import com.minifile.explorer.data.update.UpdateManager
import com.minifile.explorer.service.BlePeerCoordinator
import com.minifile.explorer.service.LocalOnlyHotspotManager
import com.minifile.explorer.service.NsdAdvertiser
import com.minifile.explorer.service.OperationRegistry
import com.minifile.explorer.service.PeerDiscoveryHolder
import com.minifile.explorer.service.PeerReceiveBridge
import com.minifile.explorer.service.WifiSpecifierConnector

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val keyValueStore = SharedPrefsKeyValueStore(appContext)
    val settingsRepository = SettingsRepository(
        store = keyValueStore,
        defaultDeviceNameProvider = { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android" },
        onLanguageChanged = { AppI18n.setSetting(it) },
    ).also { repo ->
        AppI18n.setSetting(repo.settings.value.appLanguage)
    }

    /** Settings ▸ Diagnostics: installs the process-wide diagnostic logger and syncs it with the persisted toggle. */
    val diagnostics = DiagnosticsController(appContext, settingsRepository).also { it.start() }
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
    val shareImportEngine = ShareImportEngine(fileSystem, mediaScanNotifier)
    val zipArchiver = ZipArchiver(fileSystem)
    val checksumCalculator = ChecksumCalculator(fileSystem)

    // Gallery / Photos infrastructure
    val mediaStoreIndexSource = MediaStoreIndexSource(appContext)
    val mediaStoreObserver = MediaStoreObserver(appContext)
    val galleryThumbnailCache = GalleryThumbnailCache(appContext, safFileSystem)
    val geoIndexStore = GeoIndexStore(appContext)
    val exifGeoReader = ExifGeoReader(appContext, safFileSystem, geoIndexStore)
    val losslessMp4Remuxer = LosslessMp4Remuxer(appContext, fileSystem, mediaScanNotifier, safFileSystem)
    val videoTranscoder: VideoTranscoder = VideoTranscoder.UNAVAILABLE
    val imageEditorEngine = ImageEditorEngine(
        context = appContext,
        fs = fileSystem,
        mediaScanNotifier = mediaScanNotifier,
        losslessMp4Remuxer = losslessMp4Remuxer,
    ).also { it.cleanupStaleTempFiles() }
    val galleryRepository = GalleryRepository(
        fs = fileSystem,
        primaryIndexSource = mediaStoreIndexSource,
        mediaScanNotifier = mediaScanNotifier,
        vfsSource = VfsListingIndexSource(
            fs = fileSystem,
            libraryRootsProvider = {
                val base = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
                listOf(
                    VfsPath.Local("$base/DCIM"),
                    VfsPath.Local("$base/Pictures"),
                    VfsPath.Local("$base/Movies"),
                    VfsPath.Local("$base/Download"),
                )
            },
        ),
    )
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

    // v1.0.18 Direct Hotspot + BLE + QR (spec §3).
    val nsdAdvertiser by lazy {
        NsdAdvertiser(
            context = appContext,
            settingsRepository = settingsRepository,
            peerDiscoveryHolder = peerDiscoveryHolder,
        )
    }
    val localOnlyHotspotManager by lazy {
        LocalOnlyHotspotManager(
            context = appContext,
            lanAddressResolver = lanAddressResolver,
            peerDiscoveryHolder = peerDiscoveryHolder,
        )
    }
    val wifiSpecifierConnector by lazy {
        WifiSpecifierConnector(
            context = appContext,
            peerDiscoveryHolder = peerDiscoveryHolder,
        )
    }
    val blePeerCoordinator by lazy {
        BlePeerCoordinator(
            context = appContext,
            settingsRepository = settingsRepository,
            lanAddressResolver = lanAddressResolver,
            peerDiscoveryHolder = peerDiscoveryHolder,
            hotspotManager = localOnlyHotspotManager,
            connector = wifiSpecifierConnector,
            fileSystem = fileSystem,
            mediaScanNotifier = mediaScanNotifier,
        )
    }
}
