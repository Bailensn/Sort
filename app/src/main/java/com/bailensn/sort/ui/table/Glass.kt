package com.bailensn.sort.ui.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 录一层页面内容，给顶栏、底栏、悬浮按钮当模糊源。 */
@Composable
internal fun rememberGlassBackdrop(): LayerBackdrop {
    val background = MiuixTheme.colorScheme.background
    return rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
}

/**
 * 液态玻璃：vibrancy + 模糊 + 边缘折射（lens）。
 * 半径按 dp 换算成像素——直接写 20f 是 20px，在 3x 屏上只有 7dp，看着像没糊。
 */
@Composable
private fun Modifier.liquidGlass(
    backdrop: LayerBackdrop,
    shape: Shape,
    highlight: (() -> Highlight?)?,
    shadow: (() -> Shadow?)?,
): Modifier {
    val surface = MiuixTheme.colorScheme.surface
    val canRefract = shape is CornerBasedShape
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(4f.dp.toPx())
            // lens 只认 CornerBasedShape / RoundedRectangularShape，传别的形状会在测量时抛异常。
            if (canRefract) {
                lens(refractionHeight = 16f.dp.toPx(), refractionAmount = 32f.dp.toPx())
            }
        },
        highlight = highlight,
        shadow = shadow,
        onDrawSurface = { drawRect(surface.copy(alpha = 0.28f)) },
    )
}

/** 底栏的玻璃底板。 */
@Composable
internal fun GlassBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    barShape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .liquidGlass(
                    backdrop = backdrop,
                    shape = barShape,
                    highlight = { Highlight.Plain },
                    shadow = null,
                ),
        )
        content()
    }
}

/** 液态玻璃悬浮按钮。 */
@Composable
internal fun GlassFab(
    backdrop: LayerBackdrop,
    size: Dp,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .liquidGlass(
                backdrop = backdrop,
                shape = CircleShape,
                highlight = { Highlight.Default },
                shadow = { Shadow.Default },
            )
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(size * 0.42f),
            tint = MiuixTheme.colorScheme.primary,
        )
    }
}

/**
 * 渐进模糊的参数模型：接口照 Miuix 的 `ProgressiveBlur` 抄，实现是自己写的。
 *
 * [angle] 顺时针，90 = 从上往下；模糊在 [startFraction] 处最强，到 [endFraction] 衰减为零，
 * [curve] 是衰减曲线的指数（>1 更集中在顶部，<1 更均匀）。
 */
internal data class ProgressiveBlur(
    val angle: Float = 90f,
    val startFraction: Float = 0f,
    val endFraction: Float = 1f,
    val curve: Float = 1.4f,
    val maxRadius: Dp = 18.dp,
    val steps: Int = 5,
) {

    /** 一层：模糊半径 + 这一层的遮罩从哪开始淡出。 */
    internal data class Layer(val radius: Dp, val fadeStart: Float, val fadeEnd: Float)

    /**
     * 拆成若干层叠加：半径逐层减小，遮罩的收边位置逐层往下推。
     * 层数越多过渡越顺，但每层都要多画一遍模糊，5 层是画质和开销的折中。
     */
    internal fun layers(): List<Layer> {
        val span = (endFraction - startFraction).coerceAtLeast(0.01f)
        val count = steps.coerceAtLeast(2)
        return (0 until count).map { index ->
            val progress = index / (count - 1).toFloat()
            val strength = (1f - progress).pow(curve)
            val fadeEnd = startFraction + span * (0.5f + 0.5f * progress)
            val fadeStart = startFraction + (fadeEnd - startFraction) * 0.6f
            Layer(
                radius = maxRadius * strength,
                fadeStart = fadeStart.coerceIn(0f, 1f),
                fadeEnd = fadeEnd.coerceIn(0f, 1f),
            )
        }
    }

    companion object {
        /** 上糊下清。 */
        val Top: ProgressiveBlur = ProgressiveBlur()

        /** 下糊上清。 */
        val Bottom: ProgressiveBlur = ProgressiveBlur(angle = 270f)
    }
}

/** 顶栏：渐进模糊 + 一层淡色，标题在任何照片上都读得出来。 */
@Composable
internal fun BlurBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    blur: ProgressiveBlur = ProgressiveBlur.Top,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(modifier) {
        blur.layers().forEach { layer -> BlurLayer(backdrop, blur.angle, layer) }
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(axisBrush(blur.angle, surface.copy(alpha = 0.18f), surface.copy(alpha = 0.03f)))
                },
        )
        content()
    }
}

/** 一层模糊：整块是 blur，超出 [layer] 遮罩范围的部分用渐变擦掉。 */
@Composable
private fun BoxScope.BlurLayer(backdrop: LayerBackdrop, angle: Float, layer: ProgressiveBlur.Layer) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = { blur(layer.radius.toPx()) },
                highlight = null,
                shadow = null,
            )
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = axisBrush(angle, Color.Black, Color.Black, layer.fadeStart, layer.fadeEnd),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/** 沿 [angle] 方向的线性渐变；90 度时就是在竖直方向上 0 → 1。 */
private fun DrawScope.axisBrush(
    angle: Float,
    startColor: Color,
    endColor: Color,
    fadeStart: Float = 0f,
    fadeEnd: Float = 1f,
): Brush {
    val radians = Math.toRadians(angle.toDouble())
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val center = Offset(size.width / 2f, size.height / 2f)
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val from = Offset(center.x - dx * half, center.y - dy * half)
    val to = Offset(center.x + dx * half, center.y + dy * half)
    val solidEnd = fadeEnd.coerceIn(fadeStart + 0.01f, 1f)
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to startColor,
            fadeStart.coerceIn(0f, solidEnd) to startColor,
            solidEnd to endColor,
            1f to endColor,
        ),
        start = from,
        end = to,
    )
}
