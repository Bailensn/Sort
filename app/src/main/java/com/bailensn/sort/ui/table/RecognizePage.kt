package com.bailensn.sort.ui.table

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bailensn.sort.R
import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TablePipeline
import java.util.Locale
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DividerDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 预览行数上限，超过就只提示，不把整页撑爆。 */
private const val PREVIEW_ROW_LIMIT = 40

/** 单元格尺寸固定：行高一致，表格才不会随着文字长度上下跳。 */
private val CELL_WIDTH = 104.dp
private val CELL_HEIGHT = 40.dp

@Composable
internal fun RecognizePage(
    state: TableUiState,
    viewMode: Int,
    onViewModeChange: (Int) -> Unit,
    onPick: () -> Unit,
    onRetryEngine: () -> Unit,
    onRetryRecognition: () -> Unit,
    onReset: () -> Unit,
    onImageClick: (Bitmap, Rect) -> Unit,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        when (val engine = state.engine) {
            is EngineState.Loading -> {
                item("engine-loading") {
                    NoticeCard(
                        title = engine.message ?: stringResource(R.string.model_loading),
                        progress = true,
                    )
                }
            }

            is EngineState.Failed -> {
                item("engine-failed") {
                    NoticeCard(
                        title = stringResource(R.string.model_failed_title),
                        message = engine.message,
                    )
                }
                item("engine-retry") {
                    PrimaryButton(
                        text = stringResource(R.string.action_retry),
                        onClick = onRetryEngine,
                    )
                }
            }

            is EngineState.Ready -> {
                val grid = state.grid
                val original = state.original
                if (grid == null && original == null) {
                    item("empty") {
                        NoticeCard(
                            title = stringResource(R.string.empty_title),
                            message = stringResource(R.string.empty_hint),
                        )
                    }
                    item("pick") {
                        PrimaryButton(
                            text = stringResource(R.string.action_pick),
                            onClick = onPick,
                        )
                    }
                } else {
                    // 换一张放在最顶上，普通按钮，别藏在角落。
                    if (grid != null) {
                        item("change-image") {
                            SecondaryButton(
                                text = stringResource(R.string.action_change_image),
                                onClick = onReset,
                            )
                        }
                    }
                    if (original != null) {
                        // 只有出结果（或失败要看校正效果）时才给三视图切换；
                        // 识别过程中还没生成网格图和增强图，摆个切换按钮只会让人困惑。
                        val hasViews = state.phase == TableUiState.Phase.DONE ||
                            state.phase == TableUiState.Phase.ERROR
                        item("scope-title") { SmallTitle(stringResource(R.string.label_scope)) }
                        if (hasViews) {
                            item("scope-tabs") { ViewModeTabs(viewMode, onViewModeChange) }
                        }
                        item("scope-image") {
                            ScopeCard(
                                state = state,
                                viewMode = if (hasViews) viewMode else 1,
                                onImageClick = onImageClick,
                            )
                        }
                    }

                    when (state.phase) {
                        TableUiState.Phase.RECOGNIZING -> item("progress") {
                            ProgressCard(state = state)
                        }

                        TableUiState.Phase.ERROR -> {
                            item("error") {
                                NoticeCard(
                                    title = stringResource(R.string.error_title),
                                    message = state.message ?: stringResource(R.string.error_hint),
                                )
                            }
                            item("error-retry") {
                                PrimaryButton(
                                    text = stringResource(R.string.action_retry),
                                    onClick = onRetryRecognition,
                                )
                            }
                            item("error-reset") {
                                SecondaryButton(
                                    text = stringResource(R.string.action_change_image),
                                    onClick = onReset,
                                )
                            }
                        }

                        TableUiState.Phase.DONE -> if (grid != null) {
                            // 没有过程数据（老记录）就不摆统计栏，省得显示一堆 0。
                            if (state.elapsedMs > 0) {
                                item("stats-title") { SmallTitle(stringResource(R.string.section_stats)) }
                                item("stats") { StatsCard(state = state, grid = grid) }
                            }
                            if (state.selfCheckSummary.isNotEmpty()) {
                                item("selfcheck-title") {
                                    SmallTitle(stringResource(R.string.stats_selfcheck))
                                }
                                item("selfcheck") { SelfCheckCard(state = state) }
                            }
                            item("table-title") { SmallTitle(stringResource(R.string.preview_title)) }
                            item("table") {
                                TableCard(grid = grid, onCellClick = onCellClick)
                            }
                        }

                        TableUiState.Phase.IDLE -> Unit
                    }
                }
            }
        }
    }
}

/** 网格 / 原图 / 增强 三个视图的切换，识别页和历史详情共用。 */
@Composable
internal fun ViewModeTabs(viewMode: Int, onViewModeChange: (Int) -> Unit) {
    TabRow(
        tabs = listOf(
            stringResource(R.string.view_grid),
            stringResource(R.string.view_original),
            stringResource(R.string.view_enhanced),
        ),
        selectedTabIndex = viewMode,
        onTabSelected = onViewModeChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    )
}

@Composable
internal fun ScopeCard(
    state: TableUiState,
    viewMode: Int,
    onImageClick: (Bitmap, Rect) -> Unit,
) {
    val bitmap = when (viewMode) {
        1 -> state.original
        2 -> state.enhanced ?: state.original
        else -> state.overlay ?: state.original
    } ?: return
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(8.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(
                when (viewMode) {
                    1 -> R.string.content_original
                    2 -> R.string.content_enhanced
                    else -> R.string.content_grid
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                // 固定比例：换图、切页签时卡片高度不变。
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .clickable { onImageClick(bitmap, bounds) },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
internal fun StatsCard(state: TableUiState, grid: TableGrid) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        StatRow(
            label = stringResource(R.string.stats_size),
            value = stringResource(R.string.preview_meta, grid.rowCount, grid.columnCount),
        )
        StatRow(
            label = stringResource(R.string.stats_elapsed),
            value = seconds(state.elapsedMs),
        )
        StatRow(
            label = stringResource(R.string.stats_confidence_label),
            value = String.format(Locale.US, "%.1f%%", state.averageConfidence * 100),
        )
        StatRow(
            label = stringResource(R.string.stats_corrected),
            value = state.correctedCells.toString(),
        )
        StatRow(
            label = stringResource(R.string.stats_structure_label),
            value = "${state.horizontalLines} · ${state.verticalLines} · ${state.cellCount}",
        )
    }
}

@Composable
internal fun StatRow(label: String, value: String) {
    BasicComponent(
        title = label,
        endActions = {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
    )
}

@Composable
internal fun SelfCheckCard(state: TableUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = state.selfCheckSummary,
            modifier = Modifier.padding(16.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = if (state.suspiciousRows.isEmpty()) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.error
            },
        )
    }
}

@Composable
internal fun TableCard(
    grid: TableGrid,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
) {
    // 所有行共用同一个滚动状态，左右滑动时整张表一起对齐。
    val horizontal = rememberScrollState()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        // 裁到卡片圆角内，否则首尾格子的底色会顶出圆角。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CardDefaults.CornerRadius)),
        ) {
            grid.rows.take(PREVIEW_ROW_LIMIT).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontal),
                ) {
                    row.forEachIndexed { columnIndex, cell ->
                        Box(
                            modifier = Modifier
                                .width(CELL_WIDTH)
                                .height(CELL_HEIGHT)
                                .background(
                                    if (rowIndex == 0) {
                                        MiuixTheme.colorScheme.secondaryContainer
                                    } else {
                                        MiuixTheme.colorScheme.surfaceContainerHigh
                                    },
                                )
                                // 横竖线：格子边界画一圈发丝线，行列才读得清楚。
                                .border(0.5.dp, DividerDefaults.DividerColor)
                                .clickable { onCellClick(rowIndex, columnIndex, cell) }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = cell,
                                style = MiuixTheme.textStyles.footnote1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (grid.rowCount > PREVIEW_ROW_LIMIT) {
                Text(
                    text = stringResource(R.string.preview_limit, PREVIEW_ROW_LIMIT, grid.rowCount),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 横向进度条：阶段文字 + 平滑推进的进度。 */
@Composable
private fun ProgressCard(state: TableUiState) {
    val animated by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "progress",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 转圈和进度条都留着：转圈表示还活着，进度条说明跑到哪了。
                InfiniteProgressIndicator(
                    color = MiuixTheme.colorScheme.primary,
                    size = 22.dp,
                )
                Text(text = stageLabel(state.stage), style = MiuixTheme.textStyles.body2)
            }
            LinearProgressIndicator(
                progress = animated,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NoticeCard(title: String, message: String? = null, progress: Boolean = false) {
    val leading: (@Composable () -> Unit)? = if (progress) {
        {
            InfiniteProgressIndicator(
                modifier = Modifier.padding(end = 12.dp),
                color = MiuixTheme.colorScheme.primary,
                size = 22.dp,
            )
        }
    } else {
        null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(title = title, summary = message, startAction = leading)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Text(text)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    )
}

internal fun seconds(millis: Long): String = String.format(Locale.US, "%.1f", millis / 1000.0)

@Composable
internal fun stageLabel(stage: TablePipeline.Stage?): String = stringResource(
    when (stage) {
        TablePipeline.Stage.RECTIFY -> R.string.stage_rectify
        TablePipeline.Stage.SEGMENT -> R.string.stage_segment
        TablePipeline.Stage.RECOGNIZE -> R.string.stage_recognize
        TablePipeline.Stage.VERIFY -> R.string.stage_verify
        null -> R.string.stage_idle
    },
)
