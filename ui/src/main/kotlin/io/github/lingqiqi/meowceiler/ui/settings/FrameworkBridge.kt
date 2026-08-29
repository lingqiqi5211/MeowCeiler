package io.github.lingqiqi.meowceiler.ui.settings

/**
 * 设置界面能对 Xposed 框架做的事。
 *
 * 接口在 `:ui`、实现在 `:app` —— `:ui` 不认识 libxposed，框架 API 变动牵动不到它。
 *
 * 所有操作都返回 [Result]：框架服务随时可能没连上或者中途死掉，这里不能是「安静地什么都没发生」。
 */
interface FrameworkBridge {

    /** 当前被模块注入的进程名，用于热重载前告诉用户会重载哪些。 */
    suspend fun hookedProcesses(): Result<List<String>>

    /** 对所有已注入进程请求热重载，返回成功的进程数。 */
    suspend fun hotReload(): Result<Int>

    /** 框架里当前生效的作用域。 */
    suspend fun scope(): Result<List<String>>

    /** 单独增删一个包。加包要用户在管理器里点同意，所以可能失败。 */
    suspend fun setInScope(packageName: String, inScope: Boolean): Result<Unit>
}

/** 没有框架服务时的占位，让 `:ui` 不必到处判空。 */
object NoFrameworkBridge : FrameworkBridge {
    private fun unavailable() = IllegalStateException("Xposed service unavailable")

    override suspend fun hookedProcesses(): Result<List<String>> = Result.failure(unavailable())

    override suspend fun hotReload(): Result<Int> = Result.failure(unavailable())

    override suspend fun scope(): Result<List<String>> = Result.failure(unavailable())

    override suspend fun setInScope(packageName: String, inScope: Boolean): Result<Unit> =
        Result.failure(unavailable())
}
