package io.github.lingqiqi.meowceiler.hook.util

import android.content.pm.ApplicationInfo
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/** 宿主进程自己的身份。onPackageReady 时先记一份，那时 Application 还没建好。 */
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
