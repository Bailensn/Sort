package com.bailensn.sort.ui.table

import android.graphics.Bitmap
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import com.bailensn.sort.R
import kotlinx.coroutines.launch
import kotlin.math.hypot

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 越界时按这个系数拖，松手弹回去；不是硬挡，手感才不「黏」。 */
private const val OVERSCROLL = 0.35f

/** 松手后按速度多滑一段的距离系数。 */
private const val FLING = 0.18f

/** 全屏看图：双指缩放、拖动带惯性、越界回弹；没放大时点一下关闭。 */
@Composable
internal fun ZoomableImage(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    fun limit(currentScale: Float): Offset = Offset(
        (viewport.width * (currentScale - 1f) / 2f).coerceAtLeast(0f),
        (viewport.height * (currentScale - 1f) / 2f).coerceAtLeast(0f),
    )

    fun clamp(value: Offset, currentScale: Float): Offset {
        val max = limit(currentScale)
        return Offset(value.x.coerceIn(-max.x, max.x), value.y.coerceIn(-max.y, max.y))
    }

    fun glideTo(target: Offset) {
        scope.launch {
            animate(
                typeConverter = Offset.VectorConverter,
                initialValue = offset,
                targetValue = target,
                initialVelocity = Offset.Zero,
            ) { value, _ -> offset = value }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    // 放大状态点一下不关闭，避免拖图时手一滑就退出去。
                    onTap = { if (scale <= MIN_SCALE) onTap() },
                    onDoubleTap = {
                        scale = if (scale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
                        glideTo(Offset.Zero)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    var zoomed = false
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        if (zoomChange != 1f) {
                            zoomed = true
                            scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        }
                        val pan = event.calculatePan()
                        if (pan != Offset.Zero) {
                            event.changes.firstOrNull()?.let { change ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                            }
                            val next = offset + pan
                            val max = limit(scale)
                            offset = Offset(rubber(next.x, max.x), rubber(next.y, max.y))
                        }
                        event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                    } while (event.changes.any { it.pressed })

                    // 松手：按速度补一段再回弹到边界内，别让拖动显得沉。
                    val velocity = if (zoomed) {
                        Offset.Zero
                    } else {
                        tracker.calculateVelocity().let { Offset(it.x, it.y) }
                    }
                    val fling = if (hypot(velocity.x, velocity.y) > 60f) velocity * FLING else Offset.Zero
                    glideTo(clamp(offset + fling, scale))
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.preview_fullscreen_close),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/** 越界时按 [OVERSCROLL] 衰减，边界内一比一跟手。 */
private fun rubber(value: Float, limit: Float): Float = when {
    limit <= 0f -> value * OVERSCROLL
    value > limit -> limit + (value - limit) * OVERSCROLL
    value < -limit -> -limit + (value + limit) * OVERSCROLL
    else -> value
}
