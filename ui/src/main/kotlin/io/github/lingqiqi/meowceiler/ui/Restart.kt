package io.github.lingqiqi.meowceiler.ui

import io.github.lingqiqi.meowceiler.shared.Scope
import java.io.DataOutputStream

/** 重启宿主进程，系统框架则重启设备。需要 root，失败时返回 false。 */
fun restartPackage(packageName: String): Boolean = runCatching {
    val command = if (packageName == Scope.SystemServer) "reboot" else "killall $packageName"
    val process = Runtime.getRuntime().exec("su")
    DataOutputStream(process.outputStream).use { out ->
        out.writeBytes("$command\n")
        out.writeBytes("exit\n")
        out.flush()
    }
    process.waitFor() == 0
}.getOrDefault(false)
