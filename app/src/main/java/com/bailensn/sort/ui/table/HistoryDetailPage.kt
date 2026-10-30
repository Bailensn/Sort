package com.bailensn.sort.ui.table

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bailensn.sort.R
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Share

/** 历史详情：独立一页，主页正在识别的那张图不受影响。 */
@Composable
internal fun HistoryDetailPage(
    viewModel: TableViewModel,
    entryId: Long,
    onClose: () -> Unit,
) {
    val view by viewModel.viewer.collectAsStateWithLifecycle()
    val names by viewModel.suggestions.collectAsStateWithLifecycle()

    var viewMode by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CellEdit?>(null) }
    var fullScreen by remember { mutableStateOf<Bitmap?>(null) }

    // 页面退出时把详情状态清掉，下次点开重新读盘。
    DisposableEffect(entryId) {
        onDispose { viewModel.closeHistory() }
    }

    val state = view?.let { if (it.entry.id == entryId) it.state else null }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.history_title),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (state == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 28.dp)
                }
            } else {
                val grid = state.grid
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = 96.dp,
                    ),
                ) {
                    item("scope-title") { SmallTitle(stringResource(R.string.label_scope)) }
                    item("scope-tabs") { ViewModeTabs(viewMode, { viewMode = it }) }
                    item("scope-image") {
                        ScopeCard(state = state, viewMode = viewMode) { fullScreen = it }
                    }
                    if (state.elapsedMs > 0) {
                        item("stats-title") { SmallTitle(stringResource(R.string.section_stats)) }
                        if (grid != null) {
                            item("stats") { StatsCard(state = state, grid = grid) }
                        }
                    }
                    if (state.selfCheckSummary.isNotEmpty()) {
                        item("selfcheck-title") { SmallTitle(stringResource(R.string.stats_selfcheck)) }
                        item("selfcheck") { SelfCheckCard(state = state) }
                    }
                    if (grid != null) {
                        item("table-title") { SmallTitle(stringResource(R.string.preview_title)) }
                        item("table") {
                            TableCard(grid = grid, onCellClick = { row, column, value ->
                                editing = CellEdit(row, column, value)
                            })
                        }
                    }
                }

                FloatingActionButton(
                    onClick = viewModel::exportHistory,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 24.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Share,
                        contentDescription = stringResource(R.string.action_export),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            val image = fullScreen
            if (image != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { fullScreen = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = stringResource(R.string.preview_fullscreen_close),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            editing?.let { cell ->
                CellEditSheet(
                    cell = cell,
                    names = names,
                    onDismiss = { editing = null },
                    onConfirm = { text ->
                        viewModel.updateHistoryCell(cell.row, cell.column, text)
                        editing = null
                    },
                )
            }
        }
    }
}
