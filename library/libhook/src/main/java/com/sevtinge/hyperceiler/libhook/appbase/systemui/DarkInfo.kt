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

import android.graphics.Rect

/**
 * 暗色模式信息
 *
 * 统一封装来自 `tintLightColorFlow`（Triple 模式）
 * 和 `onDarkChanged`（6 参数模式）两种来源的暗色数据。
 *
 * 子类无需关心暗色数据的来源差异，只需读取对应字段。
 *
 * @property isUseTint 是否使用 tint 着色（来自 tintLightColorFlow）
 * @property isLight 是否为浅色模式（来自 tintLightColorFlow）
 * @property color tint 颜色值（来自 tintLightColorFlow，可能为 null）
 * @property darkIntensity 暗色强度 0.0~1.0（来自 onDarkChanged）
 * @property tint 暗色 tint 值（来自 onDarkChanged）
 * @property lightColor 亮色颜色（来自 onDarkChanged 6 参数版）
 * @property darkColor 暗色颜色（来自 onDarkChanged 6 参数版）
 * @property areas 暗色区域列表（来自 onDarkChanged）
 */
data class DarkInfo(
    // tintLightColorFlow 来源
    val isUseTint: Boolean = false,
    val isLight: Boolean = true,
    val color: Int? = null,

    // onDarkChanged 来源
    val darkIntensity: Float = 0f,
    val tint: Int = 0,
    val lightColor: Int = 0,
    val darkColor: Int = 0,
    val areas: ArrayList<Rect>? = null
) {
    companion object {
        /**
         * 从 MiuiMobileIconBinder 的 tintLightColorFlow 构建
         * @param isUseTint 是否使用 tint 着色
         * @param isLight 是否为浅色模式
         * @param color tint 颜色值
         */
        @JvmStatic
        fun fromTintLightColor(isUseTint: Boolean, isLight: Boolean, color: Int?): DarkInfo {
            return DarkInfo(
                isUseTint = isUseTint,
                isLight = isLight,
                color = color,
                darkIntensity = if (isLight) 0f else 1f,
                tint = color ?: 0
            )
        }

        /**
         * 从 onDarkChanged 6 参数版构建
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun fromOnDarkChanged(
            areas: Any?,
            darkIntensity: Float,
            tint: Int,
            lightColor: Int,
            darkColor: Int,
            useTint: Boolean
        ): DarkInfo {
            return DarkInfo(
                isUseTint = useTint,
                isLight = darkIntensity <= 0f,
                color = if (useTint) tint else if (darkIntensity > 0f) darkColor else lightColor,
                darkIntensity = darkIntensity,
                tint = tint,
                lightColor = lightColor,
                darkColor = darkColor,
                areas = areas as? ArrayList<Rect>
            )
        }

        /**
         * 从 onDarkChanged 3 参数版构建
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun fromOnDarkChanged3(areas: Any?, darkIntensity: Float, tint: Int): DarkInfo {
            return DarkInfo(
                isUseTint = false,
                isLight = darkIntensity <= 0f,
                color = tint,
                darkIntensity = darkIntensity,
                tint = tint,
                areas = areas as? ArrayList<Rect>
            )
        }
    }
}
