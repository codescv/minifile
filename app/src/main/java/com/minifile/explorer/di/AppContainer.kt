package com.minifile.explorer.di

import android.content.Context
import android.os.Environment
import com.minifile.explorer.core.model.Clock
import com.minifile.explorer.core.ops.ChecksumCalculator
import com.minifile.explorer.core.ops.FileOperations
import com.minifile.explorer.core.ops.NomediaManager
import com.minifile.explorer.core.ops.ZipArchiver
import com.minifile.explorer.core.vfs.FileSystemRepository
import com.minifile.explorer.core.vfs.LocalFileSystem
import com.minifile.explorer.data.AndroidMediaScanNotifier
import com.minifile.explorer.data.AndroidVolumeProvider
import com.minifile.explorer.data.DefaultFavoritesRepository
import com.minifile.explorer.data.FavoritesRepository
import com.minifile.explorer.data.LanAddressResolver
import com.minifile.explorer.data.PersistedTreeStore
import com.minifile.explorer.data.SafFileSystem
import com.minifile.explorer.data.SettingsRepository
import com.minifile.explorer.data.SharedPrefsKeyValueStore
import com.minifile.explorer.data.update.UpdateManager
import com.minifile.explorer.service.OperationRegistry

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val keyValueStore = SharedPrefsKeyValueStore(appContext)
    val settingsRepository = SettingsRepository(keyValueStore)
    val persistedTreeStore = PersistedTreeStore(appContext, keyValueStore)

    val localFileSystem = LocalFileSystem()
    val safFileSystem = SafFileSystem(appContext)
    val fileSystem = FileSystemRepository(listOf(localFileSystem, safFileSystem))

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
}
