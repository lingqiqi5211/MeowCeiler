package io.github.lingqiqi.meowceiler.hook.base

/**
 * 功能元信息，标在每个 hooker 头上。
 *
 * SOURCE 保留：只给读代码的人和工具看，不进 dex，宿主进程零成本。
 * 这些字段不会出现在设置界面里 —— 界面上的标题与说明走 `:ui` 的字符串资源。
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class Feature(
    /** 功能名称 */
    val name: String,
    /** 引入时间 yyyy-MM-dd */
    val since: String,
    /** 适配的宿主版本，写到能定位问题的粒度 */
    val target: String,
    /** 最近更新 yyyy-MM-dd */
    val updated: String,
)
