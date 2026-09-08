package io.github.lingqiqi.meowceiler.ui.settings

/** 框架接口由 :ui 声明、:app 实现；连接和调用失败通过 [Result] 返回。 */
interface FrameworkBridge {
    /** 当前被模块注入的进程名，用于热重载前告诉用户会重载哪些。 */
    suspend fun hookedProcesses(): Result<List<String>>

    /** 重载符合条件的已注入进程，返回成功数量。 */
    suspend fun hotReload(): Result<Int>

    suspend fun scope(): Result<List<String>>

    /** 单独增删一个包。加包要用户在管理器里点同意，所以可能失败。 */
    suspend fun setInScope(packageName: String, inScope: Boolean): Result<Unit>
}

object NoFrameworkBridge : FrameworkBridge {
    private fun unavailable() = IllegalStateException("Xposed service unavailable")

    override suspend fun hookedProcesses(): Result<List<String>> = Result.failure(unavailable())

    override suspend fun hotReload(): Result<Int> = Result.failure(unavailable())

    override suspend fun scope(): Result<List<String>> = Result.failure(unavailable())

    override suspend fun setInScope(packageName: String, inScope: Boolean): Result<Unit> =
        Result.failure(unavailable())
}
