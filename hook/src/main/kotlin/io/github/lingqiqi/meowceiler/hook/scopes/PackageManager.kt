package io.github.lingqiqi.meowceiler.hook.scopes

import android.os.Build
import io.github.lingqiqi.meowceiler.hook.base.StaticHooker
import io.github.lingqiqi.meowceiler.hook.rules.packagemanager.PackageManagerCore
import io.github.lingqiqi.meowceiler.hook.rules.packagemanager.PackageManagerSwitchState

object PackageManager : StaticHooker() {
    override val extraCondition: Boolean get() = Build.VERSION.SDK_INT >= 36

    override fun onInit() {
        attach(PackageManagerCore)
    }

    internal override fun refreshAfterHotReload() {
        PackageManagerSwitchState.bind()
        super.refreshAfterHotReload()
    }
}
