package io.github.lingqiqi.meowceiler.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * 应用内语言。走系统的 per-app language，自己换 Configuration 活不过进程重建。
 * 可选项必须和 `:app` 的 `res/xml/locales_config.xml` 对得上。
 */
enum class AppLanguage(val tag: String) {
    /** 空的 LocaleList 就是「跟随系统」。 */
    System(""),
    Chinese("zh-CN"),
    English("en"),
    ;

    companion object {
        fun current(context: Context): AppLanguage {
            val locales = context.localeManager?.applicationLocales ?: return System
            val first = locales.takeIf { !it.isEmpty }?.get(0) ?: return System
            return entries.firstOrNull {
                it.tag.isNotEmpty() && it.tag.substringBefore('-') == first.language
            } ?: System
        }

        fun apply(context: Context, language: AppLanguage) {
            context.localeManager?.applicationLocales = if (language.tag.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language.tag)
            }
        }

        private val Context.localeManager: LocaleManager?
            get() = getSystemService(LocaleManager::class.java)
    }
}
