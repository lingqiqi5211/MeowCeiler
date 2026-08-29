package io.github.lingqiqi.meowceiler.ui

import java.io.DataOutputStream

/** 重启宿主进程。需要 root —— 失败时返回 false，由调用方提示。 */
fun restartPackage(packageName: String): Boolean = runCatching {
    val process = Runtime.getRuntime().exec("su")
    DataOutputStream(process.outputStream).use { out ->
        out.writeBytes("killall $packageName\n")
        out.writeBytes("exit\n")
        out.flush()
    }
    process.waitFor() == 0
}.getOrDefault(false)
