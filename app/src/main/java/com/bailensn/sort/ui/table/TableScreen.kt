package com.bailensn.sort.ui.table

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bailensn.sort.R
import com.bailensn.sort.data.HistoryStore
import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TablePipeline
import com.bailensn.sort.ui.theme.SortTheme
import com.bailensn.sort.xlsx.XlsxExporter
import java.util.Locale
import kotlin.math.abs

private const val PREVIEW_ROW_LIMIT = 40
private val CELL_WIDTH = 104.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableScreen(
    modifier: Modifier = Modifier,
    viewModel: TableViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 在组合里取文案，不要用 LocalContext.current.getString：
    // AGP 9.4 的 lint 会把后者判成「不感知配置变化」，直接报 Error。
    val chooserTitle = stringResource(R.string.share_chooser)
    var editingCell by remember { mutableStateOf<CellEdit?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var viewMode by rememberSaveable { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }
    val pick: () -> Unit = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(state.original) {
        viewMode = 0
    }

    LaunchedEffect(viewModel) {
        viewModel.exported.collect { exported ->
            runCatching {
                context.startActivity(
                    Intent.createChooser(XlsxExporter.shareIntent(exported),
                        chooserTitle,
                    ),
                )
            }
        }
    }

    editingCell?.let { cell ->
        CellEditDialog(
            cell = cell,
            suggestions = viewModel.memoryNames(),
            onDismiss = { editingCell = null },
            onConfirm = { text ->
                viewModel.updateCell(cell.row, cell.column, text)
                editingCell = null
            },
        )
    }

    if (showHistory) {
        HistoryDialog(
            entries = history,
            onDismiss = { showHistory = false },
            onOpen = { entry ->
                viewModel.openHistory(entry)
                showHistory = false
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.refreshHistory()
                            showHistory = true
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.action_history),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    if (state.original != null) {
                        TextButton(
                            onClick = viewModel::reset,
                            // 识别过程中禁掉，免得手滑把正在跑的这张换走。
                            enabled = state.phase != TableUiState.Phase.RECOGNIZING,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.action_change_image),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            BottomActionBar(
                state = state,
                onPick = pick,
                onRetryEngine = viewModel::retryEngine,
                onRetryRecognition = viewModel::retryRecognition,
                onExport = viewModel::exportXlsx,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 4.dp, bottom = 24.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (val engine = state.engine) {
                is EngineState.Loading -> LoadingBlock(engine.message)

                is EngineState.Failed -> MessageBlock(
                    title = stringResource(R.string.model_failed_title),
                    message = engine.message,
                )

                is EngineState.Ready -> ReadyContent(
                    state = state,
                    viewMode = viewMode,
                    onCycleView = { viewMode = (viewMode + 1) % 3 },
                    onCellClick = { row, column, value -> editingCell = CellEdit(row, column, value) },
                )
            }
        }
    }

}

private data class CellEdit(val row: Int, val column: Int, val value: String)

@Composable
private fun CellEditDialog(
    cell: CellEdit,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(cell.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_title, cell.row + 1, cell.column + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.edit_suggestions),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.take(12).forEach { name ->
                            TextButton(onClick = { text = name }) { Text(name) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun HistoryDialog(
    entries: List<HistoryStore.Entry>,
    onDismiss: () -> Unit,
    onOpen: (HistoryStore.Entry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_title)) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.history_empty))
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entries.forEach { entry ->
                        TextButton(
                            onClick = { onOpen(entry) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.history_entry,
                                    HistoryStore.formatTime(entry.timestamp),
                                    entry.rowCount,
                                    entry.columnCount,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun ReadyContent(
    state: TableUiState,
    viewMode: Int,
    onCycleView: () -> Unit,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
) {

    val original = state.original
    // 历史记录只有表格、没有原图：这种情况下也要把表格显示出来。
    if (original == null && state.grid == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.empty_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (original != null) {
        ImageBlock(
            state = state,
            viewMode = viewMode,
            onCycleView = onCycleView,
        )
    }

    when (state.phase) {
        TableUiState.Phase.RECOGNIZING -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SortProgressBar()
                Text(
                    text = stageLabel(state.stage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TableUiState.Phase.DONE -> {
            val grid = state.grid
            if (grid != null) {
                StatsBlock(state = state, grid = grid)
                TablePreviewCard(grid = grid, onCellClick = onCellClick)
            }
        }

        TableUiState.Phase.ERROR -> MessageBlock(
            title = stringResource(R.string.error_title),
            message = state.message ?: stringResource(R.string.error_hint),
        )

        TableUiState.Phase.IDLE -> Unit
    }
}

/**
 * 分段式进度条：两条圆头色带错位循环，主色 + 琥珀色（数据色）。
 *
 * material3 1.4.0 还没有 WavyProgressIndicator，这里用 Canvas 画同色系版本：
 * 圆头、8dp 高、带一点错位叠影，比经典 2dp 细线更有 Expressive 的分量感。
 */
@Composable
private fun SortProgressBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "progress")
    // 两段的相位差半圈，看起来像一条波在流动。
    val lead by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lead",
    )
    val trail by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(700),
        ),
        label = "trail",
    )
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp),
    ) {
        val radius = size.height / 2f
        drawRoundRect(
            color = track.copy(alpha = 0.5f),
            cornerRadius = CornerRadius(radius, radius),
        )
        // 主色带：占轨道 45%，从左侧滑出、从右侧滑入。
        val leadStart = (lead * 1.6f - 0.45f).coerceIn(-0.45f, 1f)
        drawSegment(primary, leadStart, 0.45f, radius)
        // 琥珀色带：滞后半拍，稍短、稍淡，形成叠影。
        val trailStart = (trail * 1.6f - 0.45f).coerceIn(-0.45f, 1f)
        drawSegment(accent.copy(alpha = 0.55f), trailStart, 0.28f, radius)
    }
}

private fun DrawScope.drawSegment(color: Color, startFraction: Float, widthFraction: Float, radius: Float) {
    val left = size.width * startFraction
    val right = left + size.width * widthFraction
    val clampedLeft = left.coerceIn(0f, size.width)
    val clampedRight = right.coerceIn(0f, size.width)
    if (clampedRight <= clampedLeft) return
    drawRoundRect(
        color = color,
        topLeft = Offset(clampedLeft, 0f),
        size = Size(clampedRight - clampedLeft, size.height),
        cornerRadius = CornerRadius(radius, radius),
    )
}

@Composable
private fun ImageBlock(
    state: TableUiState,
    viewMode: Int,
    onCycleView: () -> Unit,
) {
    // 0=网格（画了检测线的处理图） 1=原图 2=增强图（二遍识别看到的那张灰度图）
    val bitmap: Bitmap? = when (viewMode) {
        1 -> state.original
        2 -> state.enhanced ?: state.original
        else -> state.overlay ?: state.original
    }
    val canToggle = state.overlay != null && state.original != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.padding(start = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.label_scope),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (canToggle) {
                    TextButton(
                        onClick = onCycleView,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(
                                when (viewMode) {
                                    0 -> R.string.view_original
                                    1 -> R.string.view_enhanced
                                    else -> R.string.view_grid
                                },
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            if (bitmap != null) {
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
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun StatsBlock(state: TableUiState, grid: TableGrid) {
    val meta = buildList {
        if (state.rectified) add(stringResource(R.string.stats_rectified))
        if (abs(state.deskewAngle) >= 0.2) {
            add(
                stringResource(
                    R.string.stats_deskew,
                    String.format(Locale.US, "%.1f", state.deskewAngle),
                ),
            )
        }
    }.joinToString("，")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            StatItem(
                value = "${state.correctedCells} 处",
                label = stringResource(R.string.stats_corrected),
            )
            StatItem(
                value = String.format(Locale.US, "%.1f s", state.elapsedMs / 1000.0),
                label = stringResource(R.string.stats_elapsed),
            )
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 识别不对劲时这几个数字最有用：先看有没有线，再看时间花在哪。
        Text(
            text = stringResource(
                R.string.stats_structure,
                state.horizontalLines,
                state.verticalLines,
                state.cellCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.stats_timing,
                seconds(state.cellMs),
                seconds(state.detectMs),
                seconds(state.elapsedMs - state.cellMs),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.selfCheckSummary.isNotEmpty()) {
            Text(
                text = state.selfCheckSummary,
                style = MaterialTheme.typography.labelMedium,
                color = if (state.suspiciousRows.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        }
        // 置信度和低置信度格子数：98% 是「可信线」，低于它的格子更值得人工核对。
        Text(
            text = stringResource(
                R.string.stats_confidence,
                String.format(Locale.US, "%.1f%%", state.averageConfidence * 100),
                state.lowConfidenceCells,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun seconds(millis: Long): String =
    String.format(Locale.US, "%.1fs", millis / 1000.0)

@Composable
private fun StatItem(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TablePreviewCard(
    grid: TableGrid,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.preview_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.preview_meta, grid.rowCount, grid.columnCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                grid.rows.take(PREVIEW_ROW_LIMIT).forEachIndexed { rowIndex, row ->
                    Row(
                        modifier = Modifier.background(
                            if (rowIndex == 0) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        ),
                    ) {
                        row.forEachIndexed { columnIndex, cell ->
                            Text(
                                text = cell.ifBlank { " " },
                                modifier = Modifier
                                    .width(CELL_WIDTH)
                                    .heightIn(min = 36.dp)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    .clickable { onCellClick(rowIndex, columnIndex, cell) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (grid.rowCount > PREVIEW_ROW_LIMIT) {
                Text(
                    text = stringResource(R.string.preview_limit, PREVIEW_ROW_LIMIT, grid.rowCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun MessageBlock(title: String, message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class PrimaryAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val loading: Boolean = false,
)

@Composable
private fun BottomActionBar(
    state: TableUiState,
    onPick: () -> Unit,
    onRetryEngine: () -> Unit,
    onRetryRecognition: () -> Unit,
    onExport: () -> Unit,
) {
    val action = when {
        state.engine is EngineState.Loading -> null

        state.engine is EngineState.Failed -> PrimaryAction("重试加载", onRetryEngine)

        state.original == null -> PrimaryAction("选择图片", onPick)

        state.phase == TableUiState.Phase.RECOGNIZING -> null

        state.phase == TableUiState.Phase.ERROR ->
            PrimaryAction(stringResource(R.string.action_retry), onRetryRecognition)

        state.grid != null -> PrimaryAction(
            label = stringResource(R.string.action_export),
            onClick = onExport,
            enabled = !state.exporting,
            loading = state.exporting,
        )

        else -> null
    } ?: return

    Column {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Button(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (action.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(text = action.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun stageLabel(stage: TablePipeline.Stage?): String = stringResource(
    when (stage) {
        TablePipeline.Stage.RECTIFY -> R.string.stage_rectify
        TablePipeline.Stage.SEGMENT -> R.string.stage_segment
        TablePipeline.Stage.RECOGNIZE -> R.string.stage_recognize
        TablePipeline.Stage.VERIFY -> R.string.stage_verify
        null -> R.string.stage_idle
    },
)

@Preview(showBackground = true, backgroundColor = 0xFFF7F6F3)
@Composable
private fun TablePreviewSample() {
    SortTheme {
        Column(modifier = Modifier.padding(20.dp)) {
            // 纯虚构示例，仅用于预览排版，不要放真实学生信息。
            TablePreviewCard(
                TableGrid(
                    rows = listOf(
                        listOf("班级", "姓名", "语文", "数学"),
                        listOf("01", "示例甲", "83", "51.5"),
                        listOf("01", "示例乙", "76", "78"),
                    ),
                    mode = TableGrid.Mode.GRID_LINES,
                ),
                onCellClick = { _, _, _ -> },
            )
        }
    }
}
