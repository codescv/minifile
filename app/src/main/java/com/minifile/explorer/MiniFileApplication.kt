package com.minifile.explorer

import android.app.Application
import android.os.Build
import com.minifile.explorer.di.AppContainer
import dalvik.system.ZipPathValidator

class MiniFileApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // On Android 14+ (targetSdk >= 34), ART's default ZipPathValidator throws ZipException
        // during ZipFile/ZipInputStream entry enumeration before ZipArchiver can inspect, warn,
        // and safely skip unsafe entries (ARCHITECTURE.md §5.2 & §12.3 E11). ZipArchiver enforces
        // its own stricter sanitizeEntrySegments + canonical isInside + symlink + zip-bomb checks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ZipPathValidator.clearCallback()
        }
        // Ensure MiniFile's own external Android/data and Android/obb folders exist on disk
        runCatching {
            getExternalFilesDir(null)
            obbDir
        }
        container = AppContainer(this)
    }
}
