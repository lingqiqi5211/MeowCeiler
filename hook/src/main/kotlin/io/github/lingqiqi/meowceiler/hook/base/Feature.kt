package io.github.lingqiqi.meowceiler.hook.base

/** 功能元信息。SOURCE 保留，只给读代码的人看；界面文案走 `:ui` 的字符串资源。 */
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
