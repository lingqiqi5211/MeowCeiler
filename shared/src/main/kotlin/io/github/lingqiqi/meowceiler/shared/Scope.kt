package io.github.lingqiqi.meowceiler.shared

/** 加常量后要同步 `app/src/main/resources/META-INF/xposed/scope.list`。 */
object Scope {
    const val SystemUi = "com.android.systemui"
    const val Settings = "com.android.settings"
}

/**
 * 模块入口注入到系统设置里的位置。
 *
 * 值存字符串而不是序号：序号会随着这里增删条目而漂移，把用户的选择换成别的位置。
 */
enum class SettingsEntryPosition(val key: String) {
    Off("off"),

    /** 「我的设备」之后。锚点 `my_device`。 */
    Device("device"),

    /** 「桌面与最近任务」之后。锚点 `launcher_settings`。 */
    Launcher("launcher"),

    /** 「更多设置」之后。锚点 `other_advanced_settings`。 */
    More("more"),
    ;

    companion object {
        fun from(key: String): SettingsEntryPosition =
            entries.firstOrNull { it.key == key } ?: Off
    }
}
