package com.bailensn.sort.ui.table

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bailensn.sort.R
import com.bailensn.sort.xlsx.XlsxExporter
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PAGE_RECOGNIZE = 0
private const val PAGE_HISTORY = 1

/** 连按两次返回的间隔。 */
private const val EXIT_CONFIRM_WINDOW_MS = 2000L

/** 页面栈：主页面 → 全屏看图 / 历史详情。 */
private sealed interface Route : NavKey {
    data object Main : Route
    data class Image(val mode: Int) : Route
    data class History(val id: Long) : Route
}

@Composable
fun TableScreen(
    modifier: Modifier = Modifier,
    viewModel: TableViewModel = viewModel(),
) {
    // 只在内存里：看图、看历史都是临时页面，进程重建后回到主页面反而更合理。
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
                // 别的页面压在上面时返回手势归它，主页面不要再抢。
                backEnabled = stack.size == 1,
                onOpenImage = { mode -> stack.add(Route.Image(mode)) },
                onOpenHistory = { id -> stack.add(Route.History(id)) },
            )
        }
        entry<Route.Image>(
            transition = NavTransitions.Modal,
            // 和双指缩放/拖动画布抢手势，这里只留点按关闭和返回。
            swipeDismiss = NavSwipeDirection.None,
        ) { route ->
            ImageViewer(viewModel = viewModel, mode = route.mode, onClose = pop)
        }
        entry<Route.History>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
            HistoryDetailPage(viewModel = viewModel, entryId = route.id, onClose = pop)
        }
    }
}

@Composable
private fun MainPage(
    viewModel: TableViewModel,
    backEnabled: Boolean,
    onOpenImage: (Int) -> Unit,
    onOpenHistory: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 底栏和悬浮按钮要模糊页面内容，先把内容录一层。
    val backdrop = rememberGlassBackdrop()

    // 两个页签共用一个 Pager：滑动和点按走同一条状态，切换是连续动画而不是硬切。
    val pager = rememberPagerState(pageCount = { 2 })
    val page by remember { derivedStateOf { pager.currentPage } }

    var viewMode by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<CellEdit?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var pendingDelete by remember { mutableStateOf(emptyList<Long>()) }

    val chooserTitle = stringResource(R.string.share_chooser)
    val exportedText = stringResource(R.string.snack_exported)
    val savedText = stringResource(R.string.snack_saved)

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

    // 主页的返回全在这里收口：弹层 → 回识别页 → 再按一次退出。
    val activity = LocalActivity.current
    var exitArmed by remember { mutableStateOf(false) }
    val exitHint = stringResource(R.string.snack_exit_hint)
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            delay(EXIT_CONFIRM_WINDOW_MS)
            exitArmed = false
        }
    }

    val handleBack: () -> Unit = {
        when {
            editing != null -> editing = null
            pendingDelete.isNotEmpty() -> pendingDelete = emptyList()
            page != PAGE_RECOGNIZE -> scope.launch { pager.animateScrollToPage(PAGE_RECOGNIZE) }
            exitArmed -> activity?.finish()
            else -> {
                exitArmed = true
                scope.launch { snackbar.showSnackbar(exitHint) }
            }
        }
    }

    // 用预测性返回：手势进度直接驱动页面缩小淡出，松手才真正走上面那套逻辑。
    // Android 13+ 是系统真手势，更低的系统由 activity-compose 退化成一次完成事件。
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = backEnabled) { progress ->
        try {
            progress.collect { event -> backProgress = event.progress }
            handleBack()
        } finally {
            backProgress = 0f
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BlurBar(backdrop = backdrop) {
                SmallTopAppBar(
                    title = stringResource(
                        if (page == PAGE_HISTORY) R.string.history_title else R.string.app_name,
                    ),
                    color = Color.Transparent,
                )
            }
        },
        bottomBar = {
            // 悬浮胶囊底栏：Miuix 的 FloatingNavigationBarItem + 液态玻璃底板。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlassBar(backdrop = backdrop, barShape = RoundedCornerShape(50.dp)) {
                    BottomTabs(
                        page = page,
                        onSelect = { target -> scope.launch { pager.animateScrollToPage(target) } },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(state = snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val progress = backProgress.coerceIn(0f, 1f)
                    scaleX = 1f - 0.05f * progress
                    scaleY = 1f - 0.05f * progress
                    alpha = 1f - 0.35f * progress
                },
            ) {
            // 内容铺到屏幕底部，底栏浮在上面；列表自己留出底栏那一段。
            val pagePadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            )
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop),
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
                        onImageClick = { onOpenImage(viewMode) },
                        onCellClick = { row, column, value -> editing = CellEdit(row, column, value) },
                        contentPadding = pagePadding,
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
                            selected = emptySet()
                            viewModel.openHistory(entry)
                            onOpenHistory(entry.id)
                        },
                        onDelete = { ids -> pendingDelete = ids },
                        contentPadding = pagePadding,
                    )
                }
            }

            }

            val grid = state.grid
            if (page == PAGE_RECOGNIZE && grid != null && state.phase == TableUiState.Phase.DONE) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = padding.calculateBottomPadding() + 20.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    GlassFab(
                        backdrop = backdrop,
                        size = 60.dp,
                        icon = MiuixIcons.Share,
                        description = stringResource(R.string.action_export),
                        onClick = viewModel::exportXlsx,
                    )
                }
            }

            val cell = editing
            if (cell != null) {
                CellEditSheet(
                    cell = cell,
                    names = suggestions,
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

/** 底栏内容：玻璃底板由 [GlassBar] 提供。 */
@Composable
private fun BottomTabs(page: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .height(52.dp)
            .padding(horizontal = FloatingNavigationBarDefaults.HorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(FloatingNavigationBarDefaults.ItemSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatingNavigationBarItem(
            selected = page == PAGE_RECOGNIZE,
            onClick = { onSelect(PAGE_RECOGNIZE) },
            icon = MiuixIcons.GridView,
            label = stringResource(R.string.tab_recognize),
        )
        FloatingNavigationBarItem(
            selected = page == PAGE_HISTORY,
            onClick = { onSelect(PAGE_HISTORY) },
            icon = MiuixIcons.Timer,
            label = stringResource(R.string.tab_history),
        )
    }
}

/** 全屏看图：黑底，支持双指缩放和拖动，点一下关闭。 */
@Composable
private fun ImageViewer(viewModel: TableViewModel, mode: Int, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bitmap: Bitmap = when (mode) {
        1 -> state.original
        2 -> state.enhanced ?: state.original
        else -> state.overlay ?: state.original
    } ?: return
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ZoomableImage(bitmap = bitmap, onTap = onClose)
    }
}
