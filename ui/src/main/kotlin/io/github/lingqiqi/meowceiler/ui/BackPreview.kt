package io.github.lingqiqi.meowceiler.ui

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** 外壳与子页面共用预测式返回的位移和缩放参数。 */
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
