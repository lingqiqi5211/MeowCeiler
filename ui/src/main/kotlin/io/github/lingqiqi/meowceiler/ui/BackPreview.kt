package io.github.lingqiqi.meowceiler.ui

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 预测式返回的背景页预览：略微缩小、朝手势反方向位移并淡出，露出身后的页面。
 *
 * 外壳与子页面共用同一份参数，位移观感才一致。
 */
fun Modifier.backPreview(progress: State<Float>): Modifier = graphicsLayer {
    val value = progress.value
    if (value == 0f) return@graphicsLayer
    translationX = size.width * PreviewTranslationFraction * value
    scaleX = 1f - PreviewScaleReduction * value
    scaleY = 1f - PreviewScaleReduction * value
    alpha = 1f - PreviewAlphaReduction * value
}

private const val PreviewTranslationFraction = 0.08f
private const val PreviewScaleReduction = 0.03f
private const val PreviewAlphaReduction = 0.08f
