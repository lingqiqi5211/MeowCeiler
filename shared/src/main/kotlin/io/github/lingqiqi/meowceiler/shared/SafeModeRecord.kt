package io.github.lingqiqi.meowceiler.shared

/**
 * 一条安全模式记录：某个宿主被暂时停掉了。
 *
 * 粒度是**整个宿主**（等于一个作用域），和 HyperCeiler 一致 —— 出问题时把模块在那个宿主里
 * 的全部功能一起停掉，比留一半继续跑更安全。
 *
 * 区别在**存哪儿**：HyperCeiler 记在系统属性里（`setProp`），那要特权写、是设备级全局状态、
 * 不随模块配置走、备份/恢复/重置都覆盖不到，还得靠 root 才生效。这里记在模块自己的偏好存储里 ——
 * hook 侧和设置侧本来就共用它，顺带被 [Preferences.all] 覆盖，能备份也能重置。
 *
 * [moduleStamp] 是有效期凭据：模块一变（重装、热重载新版本）旧记录立即作废、宿主自动恢复，
 * 不用用户手动去解除。
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
 * 安全模式的**解锁前门闸**。
 *
 * 偏好存储在 CE(凭据加密)区，用户解锁之前根本读不出来；而 SystemUI 在解锁前就已经起来了。
 * 如果那个阶段模块把它崩掉，而记录只躺在读不到的偏好里，就会陷入崩溃循环 —— 连锁屏都进不去，
 * 也就没法解锁去关掉它。所以另外把「被停掉的宿主」镜像到一个 `persist.` 系统属性里：
 * 它存在 /data/property，属 DE 级，解锁前可读，且重启后仍在。这也是 HyperCeiler 用属性的原因。
 *
 * 真相来源仍是偏好（[SafeModeRecord]）；属性只是给解锁前用的缓存，由 hook 侧在偏好可读时
 * 反向对齐。值里带模块标识，模块一换整条作废。
 */
object SafeModeGate {

    /**
     * `persist.` 保证跨重启保留；`persist.service.` 这一段在 property_contexts 里是
     * system_prop，宿主进程（system UID）有机会写得进去。普通应用 UID 写不了 —— 所以只在
     * hook 侧写，并且必须当成可能失败。
     */
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
