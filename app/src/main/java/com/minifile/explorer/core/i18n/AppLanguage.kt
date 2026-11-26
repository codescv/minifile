package com.minifile.explorer.core.i18n

import java.util.Locale

/**
 * User-selectable UI language preference.
 *
 * Defaults to [SYSTEM] so the app follows the OS locale (`zh*` -> Simplified Chinese,
 * `ja*` -> Japanese, anything else -> English).
 */
enum class AppLanguage(val storageValue: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en"),
    JA("ja"),
    ;

    companion object {
        fun fromStorage(raw: String?): AppLanguage {
            val clean = raw?.trim()?.lowercase(Locale.ROOT) ?: return SYSTEM
            return when {
                clean == "system" -> SYSTEM
                clean == "zh" || clean.startsWith("zh-") || clean.startsWith("zh_") -> ZH
                clean == "ja" || clean.startsWith("ja-") || clean.startsWith("ja_") -> JA
                clean == "en" || clean.startsWith("en-") || clean.startsWith("en_") -> EN
                else -> SYSTEM
            }
        }
    }
}

/**
 * Concrete active UI language after resolving [AppLanguage.SYSTEM] against the system locale.
 */
enum class ResolvedLanguage(
    val htmlLang: String,
    val localeTag: String,
    val nativeName: String,
) {
    EN(htmlLang = "en", localeTag = "en", nativeName = "English"),
    ZH(htmlLang = "zh-CN", localeTag = "zh-CN", nativeName = "中文"),
    JA(htmlLang = "ja", localeTag = "ja", nativeName = "日本語"),
    ;

    fun toLocale(): Locale = when (this) {
        EN -> Locale.ENGLISH
        ZH -> Locale.SIMPLIFIED_CHINESE
        JA -> Locale.JAPANESE
    }
}
