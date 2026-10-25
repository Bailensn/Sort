package com.bailensn.sort.ui.table

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bailensn.sort.ocr.ModelDownloadManager
import com.bailensn.sort.util.NetworkStatus

/**
 * 高精度模型的下载状态页。
 *
 * 层级：标题 → 一句说明 → 当前状态（进度 / 原因）→ 唯一的主动作 → 次要的清除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelStatusScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by ModelDownloadManager.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("模型", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
                        Text("返回", style = MaterialTheme.typography.labelLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "高精度模型",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "约 140 MB，下载后识别更准。没下完之前一直用内置轻量模型，不影响使用。",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            when (val current = state) {
                is ModelDownloadManager.State.Downloading -> {
                    LinearProgressIndicator(
                        progress = { current.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ModelDownloadManager.State.Ready -> Text(
                    text = "已下载，正在使用高精度模型。",
                    style = MaterialTheme.typography.bodyMedium,
                )

                is ModelDownloadManager.State.Failed -> Text(
                    text = "上次下载失败：${current.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                ModelDownloadManager.State.WaitingForWifi -> Text(
                    text = if (NetworkStatus.isMetered(context)) {
                        "当前是移动数据，已暂停。连上 Wi-Fi 会自动继续。"
                    } else {
                        "等待网络，恢复后会自动继续。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ModelDownloadManager.State.Idle -> Text(
                    text = "尚未下载，当前使用内置轻量模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 唯一的主动作：按状态给出下一步。
            val primary: Pair<String, () -> Unit>? = when (state) {
                ModelDownloadManager.State.Ready -> null
                ModelDownloadManager.State.WaitingForWifi -> "用移动数据下载" to {
                    ModelDownloadManager.setAllowMetered(true)
                    ModelDownloadManager.startBackground(context)
                }
                else -> "下载高精度模型" to {
                    ModelDownloadManager.startBackground(context)
                }
            }
            if (primary != null) {
                Button(
                    onClick = primary.second,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text(primary.first, style = MaterialTheme.typography.labelLarge)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            TextButton(
                onClick = { ModelDownloadManager.clear(context) },
                modifier = Modifier.height(48.dp),
            ) {
                Text(
                    text = "清除已下载的高精度模型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
