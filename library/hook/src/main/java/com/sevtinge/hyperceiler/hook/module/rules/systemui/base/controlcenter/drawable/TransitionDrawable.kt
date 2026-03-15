/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.hook.module.rules.systemui.base.controlcenter.drawable

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import com.sevtinge.hyperceiler.hook.module.rules.systemui.base.controlcenter.mediabackground.MediaViewColorConfig
import com.sevtinge.hyperceiler.hook.utils.MathUtils.linearInterpolate
import kotlin.math.abs

// https://github.com/HowieHChen/XiaomiHelper/blob/26c33c83cc80b4c4df6237227975ad30765d4b16/app/src/main/kotlin/dev/lackluster/mihelper/hook/drawable/TransitionDrawable.kt
class TransitionDrawable(
    artwork: Drawable,
    colorConfig: MediaViewColorConfig,
    useAnim: Boolean = true
) : MediaControlBgDrawable(artwork, colorConfig, useAnim) {
    private var sourceColor: Int = colorConfig.bgStartColor
    private var currentColor: Int = colorConfig.bgStartColor
    private var targetColor: Int = colorConfig.bgStartColor

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val newSize = abs(bounds.width() - bounds.height()) / 2
        if (currentSize == 0) {
            currentSize = newSize
        }
        if (useAnim && useResizeAnim) {
            sourceSize = currentSize
            targetSize = newSize
            resizeState = AnimationState.STARTING
        } else {
            sourceSize = newSize
            currentSize = newSize
            resizeState = AnimationState.DONE
        }
        invalidateSelf()
    }

    override fun draw(p0: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return

        val now = SystemClock.elapsedRealtime()

        var alpha = 255
        when (albumState) {
            AnimationState.STARTING -> {
                albumStartTimeMillis = now
                albumState = AnimationState.RUNNING
            }
            AnimationState.RUNNING -> {
                if (albumStartTimeMillis >= 0) {
                    val normalized: Float = ((now - albumStartTimeMillis) / albumDuration.toFloat()).coerceIn(0.0f, 1.0f)
                    currentColor = argbEvaluator.evaluate(normalized, sourceColor, targetColor) as Int
                    alpha = linearInterpolate(0, 255, normalized)
                    if (normalized >= 1.0f) {
                        albumState = AnimationState.DONE
                        currentColor = targetColor
                        artwork = nextArtwork ?: artwork
                        nextArtwork = null
                        alpha = 255
                    }
                }
            }
            else -> {}
        }
        when (resizeState) {
            AnimationState.STARTING -> {
                resizeStartTimeMillis = now
                resizeState = AnimationState.RUNNING
            }
            AnimationState.RUNNING -> {
                if (resizeStartTimeMillis >= 0) {
                    val normalized: Float = ((now - resizeStartTimeMillis) / resizeDuration.toFloat()).coerceIn(0.0f, 1.0f)
                    currentSize = linearInterpolate(sourceSize, targetSize, normalized)
                    if (normalized >= 1.0f) {
                        resizeState = AnimationState.DONE
                        currentSize = targetSize
                    }
                }
            }
            else -> {}
        }
        background.color = currentColor
        background.setBounds(0, 0, bounds.width(), bounds.width())
        background.draw(p0)
        val drawBounds = Rect(0, -currentSize, bounds.width(), bounds.width() - currentSize)
        if (alpha == 0 || alpha == 255) {
            artwork.bounds = drawBounds
            artwork.draw(p0)
        } else {
            artwork.bounds = drawBounds
            artwork.alpha = 255 - alpha
            artwork.draw(p0)
            artwork.alpha = 255
            nextArtwork?.let {
                it.bounds = drawBounds
                it.alpha = alpha
                it.draw(p0)
                it.alpha = 255
            }
        }
        if (albumState != AnimationState.DONE || resizeState != AnimationState.DONE) {
            invalidateSelf()
        }
    }

    override fun updateAlbumCover(
        artwork: Drawable,
        colorConfig: MediaViewColorConfig,
        skipAnim: Boolean
    ) {
        val hostView = callback as? View
        val shouldSnap = !useAnim || skipAnim || hostView == null || !hostView.isShown || !hostView.isAttachedToWindow

        if (shouldSnap) {
            nextArtwork = null
            this.artwork = artwork
            sourceColor = colorConfig.bgStartColor
            currentColor = colorConfig.bgStartColor
            albumState = AnimationState.DONE
            this.colorConfig = colorConfig
        } else {
            nextArtwork = artwork
            sourceColor = currentColor
            targetColor = colorConfig.bgStartColor
            albumState = AnimationState.STARTING
            this.colorConfig = colorConfig
        }
        invalidateSelf()
    }
}
