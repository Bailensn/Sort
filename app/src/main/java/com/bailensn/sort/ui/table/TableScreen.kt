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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bailensn.sort.R
import com.bailensn.sort.data.HistoryStore
import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TablePipeline
import com.bailensn.sort.xlsx.XlsxExporter
import java.util.Locale
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ScanIcon: ImageVector = ImageVector.Builder(
    name = "scan",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
    ) {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(3f, 18f); lineTo(21f, 18f)
        moveTo(8f, 6f); lineTo(8f, 18f)
    }
}.build()

private val HistoryIcon: ImageVector = ImageVector.Builder(
    name = "history",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
    ) {
        moveTo(12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f)
        curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
    }
}.build()

private const val PREVIEW_ROW_LIMIT = 40
private val CELL_WIDTH = 104.dp

@Composable
fun TableScreen(
    modifier: Modifier = Modifier,
    viewModel: TableViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val chooserTitle = stringResource(R.string.share_chooser)
    val exportedText = stringResource(R.string.snack_exported)
    val savedText = stringResource(R.string.snack_saved)
    val loadedText = stringResource(R.string.snack_loaded)

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewMode by rememberSaveable { mutableStateOf(0) }
    var showViewMenu by remember { mutableStateOf(false) }
    var editingCell by remember { mutableStateOf<CellEdit?>(null) }
    var fullScreenImage by remember { mutableStateOf<Bitmap?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<List<String>>(emptyList()) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }
    val launchPick: () -> Unit = {
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(viewModel) {
        viewModel.exported.collect { exported ->
            runCatching {
                context.startActivity(Intent.createChooser(XlsxExporter.shareIntent(exported), chooserTitle))
            }
            snackbar.showSnackbar(exportedText)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = stringResource(if (tab == 0) R.string.app_name else R.string.history_title),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = ScanIcon,
                    label = stringResource(R.string.tab_recognize),
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = {
                        viewModel.refreshHistory()
                        tab = 1
                    },
                    icon = HistoryIcon,
                    label = stringResource(R.string.tab_history),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (val engine = state.engine) {
                        is EngineState.Loading -> LoadingRow(engine.message)
                        is EngineState.Failed -> HintCard(
                            title = stringResource(R.string.model_failed_title),
                            message = engine.message,
                            action = stringResource(R.string.action_retry),
                            onAction = viewModel::retryEngine,
                        )
                        is EngineState.Ready -> ReadyContent(
                            state = state,
                            viewModel = viewModel,
                            viewMode = viewMode,
                            onPick = launchPick,
                            onOpenViewMenu = { showViewMenu = true },
                            onImageClick = { fullScreenImage = it },
                            onCellClick = { row, column, value -> editingCell = CellEdit(row, column, value) },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } else {
                HistoryPage(
                    entries = history,
                    selected = selectedIds,
                    onToggle = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onSelectAll = { selectedIds = history.map { it.id }.toSet() },
                    onOpen = { entry ->
                        viewModel.openHistory(entry)
                        tab = 0
                        scope.launch { snackbar.showSnackbar(loadedText) }
                    },
                    onDelete = { ids -> pendingDelete = ids },
                )
            }

            SnackbarHost(
                state = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )

            if (showViewMenu) {
                OverlayListPopup(show = true, onDismissRequest = { showViewMenu = false }) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        listOf(R.string.view_grid, R.string.view_original, R.string.view_enhanced)
                            .forEachIndexed { index, label ->
                                TextButton(
                                    text = stringResource(label),
                                    onClick = {
                                        viewMode = index
                                        showViewMenu = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                    }
                }
            }

            editingCell?.let { cell ->
                CellEditSheet(
                    cell = cell,
                    suggestions = viewModel.memoryNames(),
                    onDismiss = { editingCell = null },
                    onConfirm = { text ->
                        viewModel.updateCell(cell.row, cell.column, text)
                        editingCell = null
                        scope.launch { snackbar.showSnackbar(savedText) }
                    },
                )
            }

            if (pendingDelete.isNotEmpty()) {
                OverlayDialog(
                    show = true,
                    title = stringResource(R.string.history_delete_title),
                    summary = stringResource(R.string.history_delete_message, pendingDelete.size),
                    onDismissRequest = { pendingDelete = emptyList() },
                    onDismissFinished = { pendingDelete = emptyList() },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { pendingDelete = emptyList() },
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                viewModel.deleteHistory(pendingDelete)
                                selectedIds = emptySet()
                                pendingDelete = emptyList()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }

    fullScreenImage?.let { bitmap ->
        FullScreenImage(bitmap = bitmap, onDismiss = { fullScreenImage = null })
    }
}

@Composable
private fun ReadyContent(
    state: TableUiState,
    viewModel: TableViewModel,
    viewMode: Int,
    onPick: () -> Unit,
    onOpenViewMenu: () -> Unit,
    onImageClick: (Bitmap) -> Unit,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
    onOpenHistory: () -> Unit = {},
) {
    if (state.original == null && state.grid == null) {
        HintCard(
            title = stringResource(R.string.empty_title),
            message = stringResource(R.string.empty_hint),
        )
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_pick))
        }
        return
    }

    when (state.phase) {
        TableUiState.Phase.RECOGNIZING -> ProgressCard(state)
        TableUiState.Phase.ERROR -> {
            HintCard(
                title = stringResource(R.string.error_title),
                message = state.message ?: stringResource(R.string.error_hint),
            )
            Button(onClick = viewModel::retryRecognition, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_retry))
            }
        }
        TableUiState.Phase.DONE -> {
            state.original?.let {
                ScopeCard(
                    state = state,
                    viewMode = viewMode,
                    onOpenViewMenu = onOpenViewMenu,
                    onImageClick = onImageClick,
                )
            }
            state.grid?.let { grid ->
                SummaryCard(state = state, grid = grid)
                TableCard(grid = grid, onCellClick = onCellClick)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = stringResource(R.string.action_change_image),
                        onClick = viewModel::reset,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = viewModel::exportXlsx,
                        enabled = !state.exporting,
                        modifier = Modifier.weight(2f),
                    ) {
                        Text(stringResource(R.string.action_export))
                    }
                }
            }
        }
        TableUiState.Phase.IDLE -> Unit
    }
}

@Composable
private fun HintCard(
    title: String,
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MiuixTheme.textStyles.title4)
            Text(text = message, style = MiuixTheme.textStyles.body2)
            if (action != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
            }
        }
    }
}

@Composable
private fun LoadingRow(message: String) {
    Card {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfiniteProgressIndicator(modifier = Modifier.width(28.dp))
            Text(text = message, style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
private fun ProgressCard(state: TableUiState) {
    Card {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfiniteProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(text = stageLabel(state.stage), style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
private fun ScopeCard(
    state: TableUiState,
    viewMode: Int,
    onOpenViewMenu: () -> Unit,
    onImageClick: (Bitmap) -> Unit,
) {
    val bitmap: Bitmap? = when (viewMode) {
        1 -> state.original
        2 -> state.enhanced ?: state.original
        else -> state.overlay ?: state.original
    }
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.label_scope),
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(
                        when (viewMode) {
                            1 -> R.string.view_original
                            2 -> R.string.view_enhanced
                            else -> R.string.view_grid
                        },
                    ),
                    onClick = onOpenViewMenu,
                )
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.content_grid),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onImageClick(bitmap) },
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(state: TableUiState, grid: TableGrid) {
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Metric("${grid.rowCount}×${grid.columnCount}", stringResource(R.string.preview_title))
                Metric(seconds(state.elapsedMs), stringResource(R.string.stats_elapsed))
                Metric("${state.correctedCells}", stringResource(R.string.stats_corrected))
            }
            Text(
                text = stringResource(
                    R.string.stats_confidence,
                    String.format(Locale.US, "%.1f%%", state.averageConfidence * 100),
                    state.lowConfidenceCells,
                ),
                style = MiuixTheme.textStyles.footnote1,
            )
            if (state.selfCheckSummary.isNotEmpty()) {
                Text(
                    text = state.selfCheckSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (state.suspiciousRows.isEmpty()) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.error
                    },
                )
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
                    seconds(state.cellMs),
                    seconds(state.detectMs),
                    seconds(state.elapsedMs - state.cellMs),
                ),
                style = MiuixTheme.textStyles.footnote2,
            )
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = value, style = MiuixTheme.textStyles.title2)
        Text(text = label, style = MiuixTheme.textStyles.footnote2)
    }
}

@Composable
private fun TableCard(
    grid: TableGrid,
    onCellClick: (row: Int, column: Int, value: String) -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.preview_title), style = MiuixTheme.textStyles.body1)
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
                                    .heightIn(min = 38.dp)
                                    .background(
                                        if (rowIndex == 0) {
                                            MiuixTheme.colorScheme.secondaryContainer
                                        } else {
                                            MiuixTheme.colorScheme.surfaceContainerHigh
                                        },
                                    )
                                    .clickable { onCellClick(rowIndex, columnIndex, cell) }
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
private fun FullScreenImage(bitmap: Bitmap, onDismiss: () -> Unit) {
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
private fun CellEditSheet(
    cell: CellEdit,
    suggestions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(cell.value) }
    OverlayBottomSheet(
        show = true,
        title = stringResource(R.string.edit_title, cell.row + 1, cell.column + 1),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = text, onValueChange = { text = it })
            if (suggestions.isNotEmpty()) {
                Text(text = stringResource(R.string.edit_suggestions), style = MiuixTheme.textStyles.footnote1)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    suggestions.take(12).forEach { name -> TextButton(text = name, onClick = { text = name }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
private fun HistoryPage(
    entries: List<HistoryStore.Entry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onOpen: (HistoryStore.Entry) -> Unit,
    onDelete: (List<String>) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entries.isEmpty()) {
            Card {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = stringResource(R.string.history_empty), style = MiuixTheme.textStyles.title4)
                    Text(text = stringResource(R.string.history_subtitle), style = MiuixTheme.textStyles.body2)
                }
            }
            return@Column
        }

        Card {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            state = if (entry.id in selected) ToggleableState.On else ToggleableState.Off,
                            onClick = { onToggle(entry.id) },
                        )
                        Text(
                            text = stringResource(
                                R.string.history_entry,
                                HistoryStore.formatTime(entry.timestamp),
                                entry.rowCount,
                                entry.columnCount,
                            ),
                            style = MiuixTheme.textStyles.body2,
                            modifier = Modifier.weight(1f).clickable { onOpen(entry) },
                        )
                    }
                }
            }
        }

        if (selected.isEmpty()) {
            TextButton(
                text = stringResource(R.string.action_select_all),
                onClick = onSelectAll,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.history_selected, selected.size),
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onDelete(selected.toList()) }) {
                    Text(stringResource(R.string.action_delete))
                }
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
