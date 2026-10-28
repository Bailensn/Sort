package com.bailensn.sort.ui.table

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bailensn.sort.R
import com.bailensn.sort.data.HistoryStore
import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TablePipeline
import com.bailensn.sort.ui.theme.SortTheme
import com.bailensn.sort.xlsx.XlsxExporter
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PREVIEW_ROW_LIMIT = 40
private val CELL_WIDTH = 104.dp

@Composable
fun TableScreen(
    modifier: Modifier = Modifier,
    viewModel: TableViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.share_chooser)

    var viewMode by rememberSaveable { mutableStateOf(0) }
    var editingCell by remember { mutableStateOf<CellEdit?>(null) }
    var fullScreenImage by remember { mutableStateOf<Bitmap?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val history by viewModel.history.collectAsStateWithLifecycle()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.exported.collect { exported ->
            runCatching {
                context.startActivity(
                    Intent.createChooser(XlsxExporter.shareIntent(exported), chooserTitle),
                )
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.app_name),
                actions = {
                    if (state.grid != null) {
                        TextButton(
                            text = stringResource(R.string.action_history),
                            onClick = {
                                viewModel.refreshHistory()
                                showHistory = true
                            },
                        )
                    }
                    if (state.original != null) {
                        TextButton(
                            text = stringResource(R.string.action_change_image),
                            onClick = viewModel::reset,
                            enabled = state.phase != TableUiState.Phase.RECOGNIZING,
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomBar(
                state = state,
                onPick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRetryEngine = viewModel::retryEngine,
                onRetryRecognition = viewModel::retryRecognition,
                onExport = viewModel::exportXlsx,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    onImageClick = { bitmap -> fullScreenImage = bitmap },
                    onPick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
        }
    }

    fullScreenImage?.let { bitmap ->
        ImagePreviewDialog(bitmap = bitmap, onDismiss = { fullScreenImage = null })
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
}

@Composable
private fun ReadyContent(
    state: TableUiState,
    viewMode: Int,
    onCycleView: () -> Unit,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
    onImageClick: (Bitmap) -> Unit,
    onPick: () -> Unit,
) {
    val original = state.original
    if (original == null && state.grid == null) {
        EmptyBlock(onPick = onPick)
        return
    }

    if (original != null) {
        ImageBlock(
            state = state,
            viewMode = viewMode,
            onCycleView = onCycleView,
            onImageClick = onImageClick,
        )
    }

    when (state.phase) {
        TableUiState.Phase.RECOGNIZING -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfiniteProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stageLabel(state.stage),
                    style = MiuixTheme.textStyles.footnote1,
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

@Composable
private fun EmptyBlock(onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.empty_title),
            style = MiuixTheme.textStyles.title3,
        )
        Text(
            text = stringResource(R.string.empty_hint),
            style = MiuixTheme.textStyles.body2,
        )
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.action_pick))
        }
    }
}

@Composable
private fun MessageBlock(title: String, message: String) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MiuixTheme.textStyles.subtitle)
            Text(text = message, style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
private fun LoadingBlock(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfiniteProgressIndicator(modifier = Modifier.width(32.dp))
        Text(text = message, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun ImageBlock(
    state: TableUiState,
    viewMode: Int,
    onCycleView: () -> Unit,
    onImageClick: (Bitmap) -> Unit,
) {
    val bitmap: Bitmap? = when (viewMode) {
        1 -> state.original
        2 -> state.enhanced ?: state.original
        else -> state.overlay ?: state.original
    }
    val canToggle = state.overlay != null && state.original != null

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.label_scope),
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.weight(1f),
                )
                if (canToggle) {
                    TextButton(
                        text = stringResource(
                            when (viewMode) {
                                0 -> R.string.view_original
                                1 -> R.string.view_enhanced
                                else -> R.string.view_grid
                            },
                        ),
                        onClick = onCycleView,
                    )
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
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onImageClick(bitmap) },
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
        if (kotlin.math.abs(state.deskewAngle) >= 0.2) {
            add(
                stringResource(
                    R.string.stats_deskew,
                    String.format(Locale.US, "%.1f", state.deskewAngle),
                ),
            )
        }
    }.joinToString("，")

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StatItem(value = "${state.correctedCells}", label = stringResource(R.string.stats_corrected))
                StatItem(value = seconds(state.elapsedMs) + "s", label = stringResource(R.string.stats_elapsed))
                StatItem(value = "${grid.rowCount}", label = stringResource(R.string.stats_rows))
            }
            if (meta.isNotEmpty()) {
                Text(text = meta, style = MiuixTheme.textStyles.footnote1)
            }
            if (state.selfCheckSummary.isNotEmpty()) {
                Text(text = state.selfCheckSummary, style = MiuixTheme.textStyles.footnote1)
            }
            Text(
                text = stringResource(
                    R.string.stats_structure,
                    state.horizontalLines,
                    state.verticalLines,
                    state.cellCount,
                ),
                style = MiuixTheme.textStyles.footnote2,
            )
            Text(
                text = stringResource(
                    R.string.stats_timing,
                    seconds(state.cellMs) + "s",
                    seconds(state.detectMs) + "s",
                    seconds(state.elapsedMs - state.cellMs) + "s",
                ),
                style = MiuixTheme.textStyles.footnote2,
            )
            Text(
                text = stringResource(
                    R.string.stats_confidence,
                    String.format(Locale.US, "%.1f%%", state.averageConfidence * 100),
                    state.lowConfidenceCells,
                ),
                style = MiuixTheme.textStyles.footnote2,
            )
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = value, style = MiuixTheme.textStyles.title2)
        Text(text = label, style = MiuixTheme.textStyles.footnote2)
    }
}

@Composable
private fun TablePreviewCard(
    grid: TableGrid,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.preview_title),
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.preview_meta, grid.rowCount, grid.columnCount),
                    style = MiuixTheme.textStyles.footnote1,
                )
            }

            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                grid.rows.take(PREVIEW_ROW_LIMIT).forEachIndexed { rowIndex, row ->
                    Row {
                        row.forEachIndexed { columnIndex, cell ->
                            Text(
                                text = cell.ifBlank { " " },
                                modifier = Modifier
                                    .width(CELL_WIDTH)
                                    .heightIn(min = 36.dp)
                                    .clickable { onCellClick(rowIndex, columnIndex, cell) }
                                    .background(
                                        if (rowIndex == 0) {
                                            MiuixTheme.colorScheme.secondaryContainer
                                        } else {
                                            Color.Transparent
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                style = MiuixTheme.textStyles.footnote1,
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
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    state: TableUiState,
    onPick: () -> Unit,
    onRetryEngine: () -> Unit,
    onRetryRecognition: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when {
            state.engine is EngineState.Failed -> Button(onClick = onRetryEngine, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_retry))
            }
            state.engine is EngineState.Loading -> Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.model_loading))
            }
            state.phase == TableUiState.Phase.RECOGNIZING -> Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.stage_idle))
            }
            state.phase == TableUiState.Phase.ERROR -> Button(
                onClick = onRetryRecognition,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_retry))
            }
            state.grid != null -> Button(
                onClick = onExport,
                enabled = !state.exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_export))
            }
            else -> Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_pick))
            }
        }
    }
}

@Composable
private fun ImagePreviewDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.preview_fullscreen_close),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
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
    OverlayDialog(
        show = true,
        title = stringResource(R.string.edit_title, cell.row + 1, cell.column + 1),
        onDismissRequest = onDismiss,
        onDismissFinished = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(value = text, onValueChange = { text = it })
            if (suggestions.isNotEmpty()) {
                Text(text = stringResource(R.string.edit_suggestions), style = MiuixTheme.textStyles.footnote1)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.take(12).forEach { name ->
                        TextButton(text = name, onClick = { text = name })
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onConfirm(text) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

@Composable
private fun HistoryDialog(
    entries: List<HistoryStore.Entry>,
    onDismiss: () -> Unit,
    onOpen: (HistoryStore.Entry) -> Unit,
) {
    OverlayDialog(
        show = true,
        title = stringResource(R.string.history_title),
        onDismissRequest = onDismiss,
        onDismissFinished = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (entries.isEmpty()) {
                Text(text = stringResource(R.string.history_empty), style = MiuixTheme.textStyles.body2)
            } else {
                entries.forEach { entry ->
                    TextButton(
                        text = stringResource(
                            R.string.history_entry,
                            HistoryStore.formatTime(entry.timestamp),
                            entry.rowCount,
                            entry.columnCount,
                        ),
                        onClick = { onOpen(entry) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

private fun seconds(millis: Long): String = String.format(Locale.US, "%.1f", millis / 1000.0)

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

@Preview(showBackground = true)
@Composable
private fun TablePreviewSample() {
    SortTheme {
        Column(modifier = Modifier.padding(20.dp)) {
            // 纯虚构示例，仅用于预览排版。
            TablePreviewCard(
                grid = TableGrid(
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
