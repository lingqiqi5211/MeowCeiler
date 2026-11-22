package io.github.lingqiqi.meowceiler

import android.content.Context
import io.github.lingqiqi.meowceiler.ui.settings.NativeWeatherBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class NativeWeatherBridgeImpl(
    context: Context,
) : NativeWeatherBridge {
    private val lifecycle = context.getSharedPreferences("native_runtime", Context.MODE_PRIVATE)
    private val directory = context.applicationInfo.nativeLibraryDir
    private val mutex = Mutex()

    private fun execute(vararg args: String): String {
        val executable = File(directory, "libmeow_runtime.so")
        check(executable.canExecute()) { "Native runtime is not packaged for this device" }
        val command = (listOf(executable.absolutePath) + args).joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            check(process.waitFor(25, TimeUnit.SECONDS)) { "Root request or native operation timed out" }
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
                    .take(4096)
            if (output.lineSequence().any { it.startsWith("COMMITTED ") }) {
                check(lifecycle.edit().putBoolean("configured", true).commit()) { "Could not persist runtime recovery state" }
            }
            check(process.exitValue() == 0) { output.ifBlank { "Root permission denied or native operation failed" } }
            val parts = output.lineSequence().last().split(' ')
            check(parts.size == 5) { "Invalid native status: $output" }
            return parts.first()
        } finally {
            process.destroy()
        }
    }

    suspend fun synchronizeIfConfigured(enabled: Boolean): Result<String>? {
        if (!lifecycle.getBoolean("configured", false)) return null
        if (lifecycle.getString("published", null) == enabled.toString()) return null
        return apply(enabled, false)
    }

    suspend fun resume(): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock { runCatching { execute("resume") } }
        }

    override suspend fun status(): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    if (lifecycle.getBoolean("configured", false)) execute("resume")
                    execute("status")
                }
            }
        }

    override suspend fun apply(
        enabled: Boolean,
        restart: Boolean,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    execute(
                        "configure",
                        File(directory, "libmeow_native_payload.so").absolutePath,
                        if (enabled) "1" else "0",
                        if (restart) "1" else "0",
                    ).also {
                        lifecycle
                            .edit()
                            .putString("published", enabled.toString())
                            .apply()
                    }
                }
            }
        }
}
