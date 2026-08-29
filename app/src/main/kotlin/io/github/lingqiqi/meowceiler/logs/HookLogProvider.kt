package io.github.lingqiqi.meowceiler.logs

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.lingqiqi.meowceiler.shared.HookLog
import io.github.lingqiqi.meowceiler.shared.ModulePackage
import io.github.lingqiqi.meowceiler.shared.Scope

/**
 * 收 hook 日志的 provider。
 *
 * 只实现 [call]，不做 CRUD —— 这不是一张表，是一条单向投递通道。
 *
 * ## 必须 exported，所以必须自己校验调用方
 *
 * 投递方是宿主进程（SystemUI / Settings），它们不可能持有模块的签名级权限，所以 provider 只能
 * exported。作为补偿，[assertCallerAllowed] 把调用方限制在 [Scope] 里的宿主和模块自己 ——
 * 任意第三方应用往这里灌日志会被直接拒掉。
 *
 * ## directBootAware
 *
 * SystemUI 在解锁前就起来了。provider 不标 directBootAware 的话，那段时间根本拉不起来，
 * 开机早期的 hook 事件全部丢失 —— 而那恰恰是最需要日志的时候。存储也因此放在 DE 区，
 * 见 [HookLogStore]。
 */
class HookLogProvider : ContentProvider() {

    private val store: HookLogStore by lazy {
        HookLogStore(requireNotNull(context) { "HookLogProvider has no context" })
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        assertCallerAllowed()
        return when (method) {
            HookLog.MethodAppend -> {
                val records = extras?.getStringArrayList(HookLog.KeyRecords).orEmpty()
                store.append(records)
                null
            }

            HookLog.MethodRead -> {
                val generations = store.read()
                Bundle().apply {
                    putStringArrayList(
                        HookLog.KeyRecords,
                        ArrayList(generations.current.map(HookLog::encode)),
                    )
                    putStringArrayList(
                        HookLog.KeyRecordsPrevious,
                        ArrayList(generations.previous.map(HookLog::encode)),
                    )
                }
            }

            HookLog.MethodClear -> {
                store.clear()
                null
            }

            else -> null
        }
    }

    /**
     * 只放行模块自己和作用域里的宿主。
     *
     * 用 uid 而不是包名比对：包名可以在 Intent 里伪造，uid 是 Binder 给的，伪造不了。
     */
    private fun assertCallerAllowed() {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return

        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        val allowed = packages.any { it == ModulePackage || it in AllowedHosts }
        require(allowed) {
            "HookLogProvider rejected uid $uid (${packages.joinToString()})"
        }
    }

    // 下面这些不实现：这个 provider 不是数据表。

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        val AllowedHosts = setOf(Scope.SystemUi, Scope.Settings)
    }
}
