package io.github.lingqiqi.meowceiler.hook.rules.settings

import android.content.Context
import android.provider.Settings.System
import io.github.lingqiqi.meowceiler.hook.R
import io.github.lingqiqi.meowceiler.hook.base.Feature
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.moduleString
import io.github.lingqiqi.meowceiler.hook.utils.settings.HostPreferences
import io.github.lingqiqi.meowceiler.shared.Preferences
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.findField
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.toClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

/**
 * 「图标显示自定义」里的通知图标个数：宿主只给 0、1、3 三档，补一个自定义档和滑条。
 *
 * 值写宿主自己的 [HostKey]，SystemUI 直接拿它当状态栏通知图标上限，不需要另外注入 SystemUI。
 */
@Feature(
    name = "通知图标个数",
    since = "2026-09-08",
    target = "Settings 17.03.260226.r",
    updated = "2026-09-08",
)
object NotificationIconCount : StaticHooker(Preferences.SettingsIcons.NotificationCount) {
    private const val HostKey = "status_bar_show_notification_icon"
    private const val DropDownKey = "show_notification_icon_count"
    private const val SliderKey = "meowceiler_notification_icon_count"
    private const val GroupKey = "meowceiler_notification_icon_group"
    private const val CustomValue = "meowceiler_custom"
    private const val CustomLabel = "settings_icons_count_custom"
    private const val UnlimitedLabel = "settings_icons_count_unlimited"
    private const val Max = 15

    /** 宿主没有「不限」这一档，用一个状态栏放不下的数代替。 */
    private const val Unlimited = 99

    override fun onHook() {
        "com.android.settings.IconDisplayCustomizationSettings".toClass()
            .findMethod { name("onCreate"); paramCount(1) }
            .createHook { after { param -> param.thisObjectOrNull?.let(::install) } }
    }

    private fun install(fragment: Any) {
        val dropDown = HostPreferences.find(fragment, DropDownKey) ?: return
        val screen = HostPreferences.screen(fragment) ?: return
        val context = fragment.callMethod("getContext") as? Context ?: return
        // getEntryValues 只在特定适配器下有返回，字段是两种适配器都会写的那一份。
        val presets = findField(dropDown.javaClass) { name("mEntryValues") }.get(dropDown) as? Array<*>
            ?: return
        val entries = dropDown.callMethod("getEntries") as? Array<*> ?: return
        if (presets.any { it?.toString() == CustomValue }) return

        val slider = createSlider(context)
        // setEntries 在默认适配器下会把 entryValues 一起改掉，顺序不能反。
        dropDown.callMethod("setEntries", appended(entries, moduleString(R.string.settings_icons_count_custom).orEmpty()))
        dropDown.callMethod("setEntryValues", appended(presets, CustomValue))
        HostPreferences.group(context, screen, order = -1, key = GroupKey, members = listOf(dropDown, slider))

        val current = System.getInt(context.contentResolver, HostKey, 1)
        val custom = presets.none { it?.toString() == current.toString() }
        slider.callMethod("setValue", if (current >= Unlimited) Max else current.coerceIn(0, Max))
        slider.callMethod("setVisible", custom)
        // 先把下拉摆到与系统值对应的那一项再挂监听：下拉首次选中会照着当前值回调一次。
        dropDown.callMethod("setValue", if (custom) CustomValue else current.toString())

        var hostListener: Any? = null
        hostListener = HostPreferences.onChange(dropDown) { value ->
            val chosen = value?.toString() == CustomValue
            slider.callMethod("setVisible", chosen)
            // 切到自定义只是把滑条露出来，值等用户拖了再写。
            if (chosen) true else HostPreferences.dispatchChange(hostListener, dropDown, value)
        }
        HostPreferences.onChange(slider) { value ->
            val icons = value as? Int ?: return@onChange false
            write(context, icons)
            true
        }
    }

    /** 不给标题：上一条下拉已经说明这是什么，滑条自己显示当前值。 */
    private fun createSlider(context: Context): Any =
        HostPreferences.createSlider(context, 0..Max) { icons ->
            if (icons >= Max) moduleString(R.string.settings_icons_count_unlimited) else null
        }.apply {
            callMethod("setKey", SliderKey)
            callMethod("setPersistent", false)
        }

    private fun write(context: Context, icons: Int) {
        val value = if (icons >= Max) Unlimited else icons
        runCatching { System.putInt(context.contentResolver, HostKey, value) }
            .onFailure { MLog.w(id, "cannot write $HostKey", it) }
    }

    private fun appended(source: Array<*>, extra: CharSequence): Array<CharSequence> =
        Array(source.size + 1) { index ->
            if (index < source.size) source[index].toString() else extra
        }
}
