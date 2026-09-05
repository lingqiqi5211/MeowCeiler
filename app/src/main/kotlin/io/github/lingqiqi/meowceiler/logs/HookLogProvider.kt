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
 * 收 hook 日志的 provider，只实现 [call]。投递方是宿主进程，拿不到模块签名权限，所以必须 exported，
 * 由 [assertCallerAllowed] 按 uid 限制在 [Scope] 里的宿主。directBootAware 且存 DE 区：SystemUI 解锁前就起来了。
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

    /** 只放行模块自己和作用域里的宿主。按 uid 比对，包名可伪造。 */
    private fun assertCallerAllowed() {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return

        val packages = context?.packageManager?.getPackagesForUid(uid).orEmpty()
        val allowed = packages.any { it == ModulePackage || it in AllowedHosts }
        require(allowed) {
            "HookLogProvider rejected uid $uid (${packages.joinToString()})"
        }
    }

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
