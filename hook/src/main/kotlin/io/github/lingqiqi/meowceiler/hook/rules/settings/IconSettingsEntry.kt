package io.github.lingqiqi.meowceiler.hook.rules.settings

import android.content.Context
import android.content.Intent
import io.github.lingqiqi.meowceiler.hook.base.Feature
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.moduleString
import io.github.lingqiqi.meowceiler.hook.utils.settings.HostPreferences
import io.github.lingqiqi.meowceiler.shared.ModuleLink
import io.github.lingqiqi.meowceiler.shared.ModuleName
import io.github.lingqiqi.meowceiler.shared.ModulePackage
import io.github.lingqiqi.meowceiler.shared.ModuleSettingsActivity
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.toClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

/** 「图标显示自定义」页顶上加一条直达模块图标页的入口；模块那边也有一条回来的。 */
@Feature(
    name = "图标设置互通入口",
    since = "2026-09-08",
    target = "Settings 17.03.260226.r",
    updated = "2026-09-08",
)
object IconSettingsEntry : StaticHooker(Preferences.SettingsIcons.Entry) {
    private const val EntryKey = "meowceiler_icon_settings"

    override fun onHook() {
        "com.android.settings.IconDisplayCustomizationSettings".toClass()
            .findMethod { name("onCreate"); paramCount(1) }
            .createHook { after { param -> param.thisObjectOrNull?.let(::install) } }
    }

    private fun install(fragment: Any) {
        if (HostPreferences.find(fragment, EntryKey) != null) return
        val screen = HostPreferences.screen(fragment) ?: return
        val context = fragment.callMethod("getContext") as? Context ?: return

        val entry = HostPreferences.create(context, HostPreferences.Plain)
        entry.callMethod("setKey", EntryKey)
        entry.callMethod("setPersistent", false)
        entry.callMethod("setTitle", ModuleName)
        moduleString(SummaryName)?.let { entry.callMethod("setSummary", it) }
        // 宿主 xml 没写 order，条目按加入顺序拿到 0 起的序号，插到最前面要给负数。
        entry.callMethod("setOrder", -2)
        HostPreferences.onClick(entry) { open(fragment) }
        screen.callMethod("addPreference", entry)
    }

    /** 模块留在自己的任务里，开过就挪到前台；返回由模块结束界面，自然退回设置。 */
    private fun open(fragment: Any) {
        val intent = Intent()
            .setClassName(ModulePackage, ModuleSettingsActivity)
            .putExtra(ModuleLink.Extra, ModuleLink.SettingsHost)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { fragment.callMethod("startActivity", intent) }
            .onFailure { MLog.w(id, "cannot open module settings", it) }
    }

    private const val SummaryName = "settings_icons_entry_summary"
}
