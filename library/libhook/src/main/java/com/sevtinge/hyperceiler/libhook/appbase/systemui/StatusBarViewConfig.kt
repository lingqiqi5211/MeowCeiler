/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.appbase.systemui

/**
 * 状态栏视图 Hook 配置
 *
 * 通过 DSL 构建：
 * ```kotlin
 * statusBarViewConfig {
 *     hookPoint = HookPoint.MOBILE_CONSTRUCT_AND_BIND
 *     darkModeStrategy = DarkModeStrategy.TINT_LIGHT_COLOR_FLOW
 *     targetSlot = "mobile"
 * }
 * ```
 */
class StatusBarViewConfig private constructor(builder: Builder) {

    /**
     * Hook 入口类型
     */
    enum class HookPoint {
        /**
         * Hook ModernStatusBarMobileView.constructAndBind()
         * 适用于移动信号图标相关修改（双排信号、网络类型等）
         */
        MOBILE_CONSTRUCT_AND_BIND,

        /**
         * Hook 指定 View 类的所有构造函数
         * 适用于 NetworkSpeedView、时钟等已有视图的修改
         * 需要配合 [viewClass] 使用
         */
        VIEW_ALL_CONSTRUCTORS,

        /**
         * Hook StatusBarIconControllerImpl 的 addIconGroup/setIcon
         * 适用于需要监听图标注册和变更事件的场景
         */
        ICON_CONTROLLER
    }

    /**
     * 暗色模式适配策略
     */
    enum class DarkModeStrategy {
        /**
         * 通过 MiuiMobileIconBinder 的 tintLightColorFlow 收集暗色变化
         * 提供 isUseTint/isLight/color 三元组
         * 仅适用于 mobile 信号相关视图
         */
        TINT_LIGHT_COLOR_FLOW,

        /**
         * 通过 Hook 视图的 onDarkChanged(6 参数) 方法监听暗色变化
         * 提供 darkIntensity/tint/areas 信息
         * 适用于所有实现 DarkReceiver 接口的状态栏视图
         */
        ON_DARK_CHANGED,

        /**
         * 不需要暗色模式适配
         */
        NONE
    }

    /** Hook 入口类型 */
    val hookPoint: HookPoint = builder.hookPoint

    /** 暗色模式适配策略 */
    val darkModeStrategy: DarkModeStrategy = builder.darkModeStrategy

    /** 目标插槽名称（如 "mobile"、"network_speed"）*/
    val targetSlot: String = builder.targetSlot

    /**
     * 目标 View 类
     * 仅 [HookPoint.VIEW_ALL_CONSTRUCTORS] 模式下需要
     */
    val viewClass: Class<*>? = builder.viewClass

    /**
     * subId 过滤器
     * 仅 [HookPoint.MOBILE_CONSTRUCT_AND_BIND] 模式下有效
     * 返回 true 表示处理该 subId 的视图
     * 为 null 表示处理所有 subId
     */
    val subIdFilter: ((Int) -> Boolean)? = builder.subIdFilter

    /**
     * 是否在 onViewCreated 中使用 OnLayoutChangeListener
     * 适用于需要在布局完成后修改视图的场景（如 NetworkSpeedView 样式修改）
     * 默认为 false（在构造函数/constructAndBind 的 after 中直接执行）
     */
    val useLayoutListener: Boolean = builder.useLayoutListener

    class Builder {
        var hookPoint: HookPoint = HookPoint.MOBILE_CONSTRUCT_AND_BIND
        var darkModeStrategy: DarkModeStrategy = DarkModeStrategy.NONE
        var targetSlot: String = ""
        var viewClass: Class<*>? = null
        var subIdFilter: ((Int) -> Boolean)? = null
        var useLayoutListener: Boolean = false

        fun build(): StatusBarViewConfig {
            when (hookPoint) {
                HookPoint.VIEW_ALL_CONSTRUCTORS -> {
                    requireNotNull(viewClass) {
                        "viewClass must be set when hookPoint is VIEW_ALL_CONSTRUCTORS"
                    }
                }
                HookPoint.MOBILE_CONSTRUCT_AND_BIND -> {
                    if (targetSlot.isEmpty()) targetSlot = "mobile"
                }
                else -> {}
            }
            return StatusBarViewConfig(this)
        }
    }

    override fun toString(): String {
        return "StatusBarViewConfig(hookPoint=$hookPoint, darkModeStrategy=$darkModeStrategy, " +
            "targetSlot='$targetSlot', viewClass=${viewClass?.simpleName}, " +
            "useLayoutListener=$useLayoutListener)"
    }
}

/**
 * DSL 函数，用于快速构建 [StatusBarViewConfig]
 */
inline fun statusBarViewConfig(block: StatusBarViewConfig.Builder.() -> Unit): StatusBarViewConfig {
    return StatusBarViewConfig.Builder().apply(block).build()
}
