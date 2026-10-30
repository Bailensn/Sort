package com.bailensn.sort.ui.table

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
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
 * 液态玻璃的一整套效果：vibrancy + 模糊 + 边缘折射（lens）。
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

/**
 * 顶栏 / 底栏的玻璃底板。
 *
 * [fadeBottom] 让玻璃自上往下渐隐：顶栏直接切在内容上会有一条硬边，
 * 渐隐之后滚动内容从模糊过渡到清晰。
 */
@Composable
internal fun GlassBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    barShape: Shape = RectangleShape,
    fadeBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        // 玻璃单独一层，渐隐只作用在模糊上，标题文字不跟着变淡。
        Box(
            modifier = Modifier
                .matchParentSize()
                .liquidGlass(
                    backdrop = backdrop,
                    shape = barShape,
                    highlight = { Highlight.Plain },
                    shadow = null,
                )
                .then(if (fadeBottom) Modifier.fadeOutBottom() else Modifier),
        )
        content()
    }
}

/** 底部渐隐的遮罩：用 DstIn 把玻璃层的下半段擦成透明。 */
private fun Modifier.fadeOutBottom(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                0.55f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
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

/** 顶栏专用：只做高斯模糊和一层淡色，不做折射高光；底部渐隐避免硬边。 */
@Composable
internal fun BlurBar(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(18f.dp.toPx()) },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(surface.copy(alpha = 0.18f)) },
                )
                .fadeOutBottom(),
        )
        content()
    }
}
