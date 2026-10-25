package com.bailensn.sort.ui.table

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
                    Intent.createChooser(XlsxExporter.shareIntent(exported), "导出表格"),
                )
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sort",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    if (state.original != null) {
                        TextButton(
                            onClick = viewModel::reset,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("换一张", style = MaterialTheme.typography.labelLarge)
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
                    title = "模型加载失败",
                    message = engine.message,
                )

                is EngineState.Ready -> ReadyContent(
                    state = state,
                    viewMode = viewMode,
                    onCycleView = { viewMode = (viewMode + 1) % 3 },
                )
            }
        }
    }

}

@Composable
private fun ReadyContent(
    state: TableUiState,
    viewMode: Int,
    onCycleView: () -> Unit,
) {

    val original = state.original
    if (original == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "选择一张表格图片",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "有框线的成绩单、报表识别最准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    ImageBlock(
        state = state,
        viewMode = viewMode,
        onCycleView = onCycleView,
    )

    when (state.phase) {
        TableUiState.Phase.RECOGNIZING -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
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
                TablePreviewCard(grid = grid)
            }
        }

        TableUiState.Phase.ERROR -> MessageBlock(
            title = "识别失败",
            message = state.message ?: "换一张清晰的照片试试",
        )

        TableUiState.Phase.IDLE -> Unit
    }
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
                    text = "识别范围",
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
                            text = when (viewMode) {
                                0 -> "看原图"
                                1 -> "看增强"
                                else -> "看网格"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = when (viewMode) {
                        1 -> "原始表格图片"
                        2 -> "增强后的表格图片"
                        else -> "表格识别网格"
                    },
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
        if (state.rectified) add("已做透视校正")
        if (abs(state.deskewAngle) >= 0.2) {
            add("已纠偏 " + String.format(Locale.US, "%.1f", state.deskewAngle) + "°")
        }
    }.joinToString("，")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            StatItem(
                value = "${state.correctedCells} 处",
                label = "回读纠正",
            )
            StatItem(
                value = String.format(Locale.US, "%.1f s", state.elapsedMs / 1000.0),
                label = "用时",
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
            text = "横线 ${state.horizontalLines} · 竖线 ${state.verticalLines} · 格子 ${state.cellCount}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "逐格 ${seconds(state.cellMs)} · 整图检测 ${seconds(state.detectMs)} · " +
                "结构 ${seconds(state.elapsedMs - state.cellMs)}",
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
private fun TablePreviewCard(grid: TableGrid) {
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
                    text = "表格预览",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${grid.rowCount} 行 ${grid.columnCount} 列",
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
                        row.forEach { cell ->
                            Text(
                                text = cell.ifBlank { " " },
                                modifier = Modifier
                                    .width(CELL_WIDTH)
                                    .heightIn(min = 36.dp)
                                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    text = "仅预览前 $PREVIEW_ROW_LIMIT 行，导出包含全部 ${grid.rowCount} 行",
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

        state.phase == TableUiState.Phase.ERROR -> PrimaryAction("重新识别", onRetryRecognition)

        state.grid != null -> PrimaryAction(
            label = "导出 Excel",
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

private fun stageLabel(stage: TablePipeline.Stage?): String = when (stage) {
    TablePipeline.Stage.RECTIFY -> "正在矫正图片"
    TablePipeline.Stage.SEGMENT -> "正在切分表格"
    TablePipeline.Stage.RECOGNIZE -> "正在识别文字"
    TablePipeline.Stage.VERIFY -> "正在回读核对"
    null -> "正在处理"
}

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
            )
        }
    }
}
