package io.github.lingqiqi.meowceiler.ui.settings

interface NativeWeatherBridge {
    suspend fun status(): Result<String>

    suspend fun apply(
        enabled: Boolean,
        restart: Boolean,
    ): Result<String>
}

object NoNativeWeatherBridge : NativeWeatherBridge {
    override suspend fun status(): Result<String> = Result.failure(IllegalStateException("Native bridge unavailable"))

    override suspend fun apply(
        enabled: Boolean,
        restart: Boolean,
    ) = status()
}
