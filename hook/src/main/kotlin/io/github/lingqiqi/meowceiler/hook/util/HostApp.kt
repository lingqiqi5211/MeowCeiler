package io.github.lingqiqi.meowceiler.hook.util

import android.content.pm.ApplicationInfo
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/**
 * 当前宿主进程自己的身份。
 *
 * `onPackageReady` 时记一份，是因为那时候 Application 还没建好、[EzXposed.appContextOrNull]
 * 还是 null，而 hook 装载恰恰发生在那之后不久。热重载不会重放 `onPackageReady`，那时候
 * Application 早就在了，所以回落到 context 那条就够。
 */
object HostApp {

    @Volatile
    private var stashed: ApplicationInfo? = null

    fun attach(applicationInfo: ApplicationInfo) {
        stashed = applicationInfo
    }

    /** 拿不到就是 null —— 调用方自己决定是降级还是放弃，不要在这里造一个假的。 */
    val applicationInfo: ApplicationInfo?
        get() = stashed ?: EzXposed.appContextOrNull?.applicationInfo
}
