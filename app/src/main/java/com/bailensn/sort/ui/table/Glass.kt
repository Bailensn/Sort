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
 * 顶栏用的渐隐遮罩：沿 [angle] 方向从 [startAlpha] 淡到 [endAlpha]。
 *
 * 之前这里是模糊（5 层 → 1 层 → 干脆不要了）：RenderEffect 每帧都要过一遍 GPU，
 * 滚动时肉眼可见地掉帧，而顶栏只是给标题垫个底，一条渐变就够了。
 *
 * [angle] 顺时针，90 = 从上往下；渐变在 [fadeStart] 之前是满的，到 [fadeEnd] 归零。
 */
internal data class EdgeScrim(
    val angle: Float = 90f,
    val fadeStart: Float = 0f,
    val fadeEnd: Float = 1f,
    val startAlpha: Float = 0.55f,
    val endAlpha: Float = 0f,
) {
    companion object {
        /** 上浓下透明。 */
        val Top: EdgeScrim = EdgeScrim()

        /** 下浓上透明。 */
        val Bottom: EdgeScrim = EdgeScrim(angle = 270f)
    }
}

/** 顶栏：一层渐隐遮罩，零 GPU 模糊。 */
@Composable
internal fun ScrimBar(
    modifier: Modifier = Modifier,
    scrim: EdgeScrim = EdgeScrim.Top,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        axisBrush(
                            scrim.angle,
                            surface.copy(alpha = scrim.startAlpha),
                            surface.copy(alpha = scrim.endAlpha),
                            scrim.fadeStart,
                            scrim.fadeEnd,
                        ),
                    )
                },
        )
        content()
    }
}

/** 沿 [angle] 方向的线性渐变；90 度时就是竖直方向 0 → 1。 */
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
