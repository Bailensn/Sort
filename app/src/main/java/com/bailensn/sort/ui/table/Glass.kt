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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
 * 顶栏：自上往下逐级减弱的高斯模糊（progressive blur）。
 *
 * Miuix 的 `progressiveTextureBlur(ProgressiveBlur.Top)` 在 `miuix-blur` 里，那个模块
 * minSdk 是 33，我们用不了；这里按同样的思路自己叠：越强的模糊盖在越上面，
 * 每层用错开的渐变遮罩收边，叠出来就是从糊到清晰的连续过渡，没有硬边。
 */
@Composable
internal fun BlurBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(modifier) {
        // 从弱到强由下往上叠：弱的先画、盖得最远，强的压在最上面。
        BlurLayer(backdrop, radius = 3.dp, fadeStart = 0.72f, fadeEnd = 1f)
        BlurLayer(backdrop, radius = 10.dp, fadeStart = 0.42f, fadeEnd = 0.78f)
        BlurLayer(backdrop, radius = 20.dp, fadeStart = 0.14f, fadeEnd = 0.52f)
        // 一层很淡的表面色，保证标题在任何照片上都读得出来。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to surface.copy(alpha = 0.20f),
                        1f to surface.copy(alpha = 0.04f),
                    ),
                ),
        )
        content()
    }
}

/** 一层模糊：整块是 blur，超出 [fadeStart]..[fadeEnd] 的部分用渐变吃掉。 */
@Composable
private fun BoxScope.BlurLayer(
    backdrop: LayerBackdrop,
    radius: Dp,
    fadeStart: Float,
    fadeEnd: Float,
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = { blur(radius.toPx()) },
                highlight = null,
                shadow = null,
            )
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        fadeStart to Color.Black,
                        fadeEnd to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}
