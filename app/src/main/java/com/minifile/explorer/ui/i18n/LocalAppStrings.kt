package com.minifile.explorer.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.minifile.explorer.core.i18n.AppI18n
import com.minifile.explorer.core.i18n.AppStrings
import com.minifile.explorer.core.i18n.ResolvedLanguage

val LocalAppStrings = staticCompositionLocalOf<AppStrings> {
    AppI18n.stringsFor(ResolvedLanguage.EN)
}
