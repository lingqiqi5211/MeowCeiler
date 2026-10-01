package io.github.lingqiqi.meowceiler.hook.utils.systemui

import android.os.Bundle
import io.github.lingqiqi.meowceiler.hook.util.MLog
import io.github.lingqiqi.meowceiler.hook.util.Settings
import io.github.lingqiqi.meowceiler.shared.FocusAppRegistry
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal object FocusAppReporter {
    private val accepted = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    fun record(
        kind: String,
        packageName: String?,
    ) {
        if (!FocusAppRegistry.isValid(kind, packageName)) return
        val key = "$kind:$packageName"
        if (key in accepted || !pending.add(key)) return
        Settings.scope.launch {
            try {
                repeat(3) { attempt ->
                    val result =
                        runCatching {
                            EzXposed.appContextOrNull
                                ?.contentResolver
                                ?.call(
                                    HookLog.Authority,
                                    FocusAppRegistry.MethodRecord,
                                    packageName,
                                    Bundle().apply { putString(FocusAppRegistry.KeyKind, kind) },
                                )?.getBoolean(FocusAppRegistry.KeyAccepted) == true
                        }
                    if (result.getOrDefault(false)) {
                        accepted.add(key)
                        MLog.i("systemui_focus_unlock", "recorded $kind app $packageName")
                        return@launch
                    }
                    if (attempt < 2) {
                        delay(1000L * (attempt + 1))
                    } else {
                        MLog.w(
                            "systemui_focus_unlock",
                            "cannot record $kind app $packageName: ${result.exceptionOrNull() ?: "not acknowledged"}",
                        )
                    }
                }
            } finally {
                pending.remove(key)
            }
        }
    }
}
