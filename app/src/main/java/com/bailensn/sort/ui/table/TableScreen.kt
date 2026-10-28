package com.bailensn.sort.ui.table

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bailensn.sort.R
import com.bailensn.sort.xlsx.XlsxExporter
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PAGE_RECOGNIZE = 0
private const val PAGE_HISTORY = 1

/** 页面栈只有两级：主页面 + 全屏看图。 */
private sealed interface Route : NavKey {
    data object Main : Route
    data class Image(val mode: Int) : Route
}

@Composable
fun TableScreen(
    modifier: Modifier = Modifier,
    viewModel: TableViewModel = viewModel(),
) {
    // 只在内存里：看图是临时页面，进程重建后回到主页面反而更合理。
    val stack = remember { navBackStackOf(Route.Main) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    NavDisplay(
        backStack = stack,
        modifier = modifier.fillMaxSize(),
        onBack = pop,
        effects = NavDisplayEffects(
            enableCornerClip = false,
            backdropColor = MiuixTheme.colorScheme.background,
            blockInputDuringTransition = true,
        ),
    ) {
        entry<Route.Main> {
            MainPage(
                viewModel = viewModel,
                // 看图页压在上面时返回手势归它，主页面不要再抢。
                backEnabled = stack.size == 1,
                onOpenImage = { mode -> stack.add(Route.Image(mode)) },
            )
        }
        entry<Route.Image>(
            transition = NavTransitions.Modal,
            swipeDismiss = NavSwipeDirection.TopToBottom,
        ) { route ->
            ImageViewer(viewModel = viewModel, mode = route.mode, onClose = pop)
        }
    }
}

@Composable
private fun MainPage(
    viewModel: TableViewModel,
    backEnabled: Boolean,
    onOpenImage: (Int) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 两个页签共用一个 Pager：滑动和点按走同一条状态，切换是连续动画而不是硬切。
    val pager = rememberPagerState(pageCount = { 2 })
    val page by remember { derivedStateOf { pager.currentPage } }

    var viewMode by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CellEdit?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf(emptyList<String>()) }

    val chooserTitle = stringResource(R.string.share_chooser)
    val exportedText = stringResource(R.string.snack_exported)
    val savedText = stringResource(R.string.snack_saved)
    val loadedText = stringResource(R.string.snack_loaded)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onImagePicked(uri)
        scope.launch { pager.animateScrollToPage(PAGE_RECOGNIZE) }
    }

    // 滑到历史页才刷新：点按和滑动都走这里，不用两处各写一遍。
    LaunchedEffect(page) {
        if (page == PAGE_HISTORY) viewModel.refreshHistory()
    }

    LaunchedEffect(viewModel) {
        viewModel.exported.collect { exported ->
            runCatching {
                context.startActivity(
                    Intent.createChooser(XlsxExporter.shareIntent(exported), chooserTitle),
                )
            }
            snackbar.showSnackbar(exportedText)
        }
    }

    val canGoBack = editing != null || pendingDelete.isNotEmpty() || page != PAGE_RECOGNIZE
    BackHandler(enabled = backEnabled && canGoBack) {
        when {
            editing != null -> editing = null
            pendingDelete.isNotEmpty() -> pendingDelete = emptyList()
            else -> scope.launch { pager.animateScrollToPage(PAGE_RECOGNIZE) }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = stringResource(
                    if (page == PAGE_HISTORY) R.string.history_title else R.string.app_name,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == PAGE_RECOGNIZE,
                    onClick = { scope.launch { pager.animateScrollToPage(PAGE_RECOGNIZE) } },
                    icon = MiuixIcons.GridView,
                    label = stringResource(R.string.tab_recognize),
                )
                NavigationBarItem(
                    selected = page == PAGE_HISTORY,
                    onClick = { scope.launch { pager.animateScrollToPage(PAGE_HISTORY) } },
                    icon = MiuixIcons.Timer,
                    label = stringResource(R.string.tab_history),
                )
            }
        },
        snackbarHost = { SnackbarHost(state = snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().padding(padding),
                beyondViewportPageCount = 1,
            ) { index ->
                if (index == PAGE_RECOGNIZE) {
                    RecognizePage(
                        state = state,
                        viewMode = viewMode,
                        onViewModeChange = { viewMode = it },
                        onPick = {
                            pick.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onRetryEngine = viewModel::retryEngine,
                        onRetryRecognition = viewModel::retryRecognition,
                        onReset = viewModel::reset,
                        onExport = viewModel::exportXlsx,
                        onImageClick = { onOpenImage(viewMode) },
                        onCellClick = { row, column, value -> editing = CellEdit(row, column, value) },
                    )
                } else {
                    HistoryPage(
                        entries = history,
                        selected = selected,
                        onToggle = { id ->
                            selected = if (id in selected) selected - id else selected + id
                        },
                        onSelectAll = { selected = history.map { it.id }.toSet() },
                        onClearSelection = { selected = emptySet() },
                        onOpen = { entry ->
                            viewModel.openHistory(entry)
                            selected = emptySet()
                            scope.launch {
                                pager.animateScrollToPage(PAGE_RECOGNIZE)
                                snackbar.showSnackbar(loadedText)
                            }
                        },
                        onDelete = { ids -> pendingDelete = ids },
                    )
                }
            }

            val cell = editing
            if (cell != null) {
                CellEditSheet(
                    cell = cell,
                    suggestions = suggestions,
                    onDismiss = { editing = null },
                    onConfirm = { text ->
                        viewModel.updateCell(cell.row, cell.column, text)
                        editing = null
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = { pendingDelete = emptyList() },
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                viewModel.deleteHistory(pendingDelete)
                                pendingDelete = emptyList()
                                selected = emptySet()
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
}

/** 全屏看图：黑底、点一下关闭；下滑和系统返回由页面栈接管。 */
@Composable
private fun ImageViewer(viewModel: TableViewModel, mode: Int, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bitmap: Bitmap = when (mode) {
        1 -> state.original
        2 -> state.enhanced ?: state.original
        else -> state.overlay ?: state.original
    } ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.preview_fullscreen_close),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            contentScale = ContentScale.Fit,
        )
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
    var text by remember(cell) { mutableStateOf(cell.value) }
    OverlayBottomSheet(
        show = true,
        title = stringResource(R.string.edit_title, cell.row + 1, cell.column + 1),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
            )
            if (suggestions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.edit_suggestions),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.take(12).forEach { name ->
                        TextButton(text = name, onClick = { text = name })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onConfirm(text) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
