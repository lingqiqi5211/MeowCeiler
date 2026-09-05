package io.github.lingqiqi.meowceiler.shared

/**
 * 一条安全模式记录：某个宿主被暂时停掉。粒度是整个宿主。记在模块自己的偏好里而不是系统属性，
 * 能备份、重置。[moduleStamp] 是有效期：模块一换旧记录作废，宿主自动恢复。
 */
data class SafeModeRecord(
    /** 被停掉的宿主包名，取值见 [Scope]。 */
    val hostPackage: String,
    /** 累计失败次数。到 [FailureThreshold] 才真的停，偶发一次不算。 */
    val failures: Int,
    /** 记录时模块 apk 的标识（文件修改时间）。和当前不一致就说明模块换过了。 */
    val moduleStamp: String,
    /** 最后一次异常的简述，给用户和作者看是哪儿炸的。 */
    val message: String,
) {
    val blocked: Boolean get() = failures >= FailureThreshold

    fun encode(): String = listOf(
        hostPackage,
        failures.toString(),
        moduleStamp,
        message.sanitized(),
    ).joinToString(Separator)

    companion object {
        /** 累计失败到这个次数才停掉宿主。 */
        const val FailureThreshold = 3

        /**
         * 记录之间的字段分隔符。
         *
         * 用制表符：包名、数字、时间戳里都不会有它，异常简述里万一有也会被 [sanitized] 换掉。
         */
        private const val Separator = "\t"
        private const val MaxMessage = 200

        fun decode(raw: String): SafeModeRecord? {
            val parts = raw.split(Separator)
            if (parts.size != 4) return null
            return SafeModeRecord(
                hostPackage = parts[0].takeIf { it.isNotBlank() } ?: return null,
                failures = parts[1].toIntOrNull() ?: return null,
                moduleStamp = parts[2],
                message = parts[3],
            )
        }

        /** 分隔符和换行都要清掉，否则一条记录会被拆成两条。 */
        private fun String.sanitized(): String = replace(Separator, " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MaxMessage)
    }
}

/**
 * 安全模式的解锁前门闸。偏好在 CE 区解锁前读不出来，而 SystemUI 那时已经起来了，崩了会进崩溃循环；
 * 所以把「被停掉的宿主」镜像到 `persist.` 属性，DE 级、解锁前可读。真相来源仍是偏好，属性由 hook 侧反向对齐。
 */
object SafeModeGate {
    /** `persist.service.` 段是 system_prop，宿主进程（system UID）能写；模块自己写不了，只在 hook 侧写且可能失败。 */
    const val PropertyKey = "persist.service.meowceiler.safemode"

    private const val StampSeparator = ":"
    private const val PackageSeparator = ","

    fun encode(moduleStamp: String, blocked: Set<String>): String =
        if (blocked.isEmpty()) "" else moduleStamp + StampSeparator + blocked.sorted().joinToString(PackageSeparator)

    /** 模块标识对不上就返回空集合 —— 换过模块，旧门闸不再作数。 */
    fun decode(raw: String, moduleStamp: String): Set<String> {
        val stamp = raw.substringBefore(StampSeparator, missingDelimiterValue = "")
        if (stamp.isEmpty() || stamp != moduleStamp) return emptySet()
        return raw.substringAfter(StampSeparator)
            .split(PackageSeparator)
            .filter { it.isNotBlank() }
            .toSet()
    }
}
