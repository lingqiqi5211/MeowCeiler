package io.github.lingqiqi.meowceiler.hook.base

/** 仅保留于源码的功能元信息；日期使用 yyyy-MM-dd，target 记录宿主版本。 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class Feature(
    val name: String,
    val since: String,
    val target: String,
    val updated: String,
)
