package com.bailensn.sort.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bailensn.sort.NativeBridge
import com.bailensn.sort.ui.theme.SortTheme

private val SampleValues = intArrayOf(42, 7, 19, 3, 88, 23, 56, 1, 64, 12)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortApp(modifier: Modifier = Modifier) {
    var counter by rememberSaveable { mutableIntStateOf(0) }
    var sortedValues by remember { mutableStateOf<List<Int>?>(null) }
    var sortError by remember { mutableStateOf<String?>(null) }

    val nativeInfo = remember { loadNativeInfo() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Sort", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NativeInfoCard(info = nativeInfo)

            SortCard(
                values = SampleValues,
                sorted = sortedValues,
                error = sortError,
                onSort = {
                    runCatching { NativeBridge.sort(SampleValues).toList() }
                        .onSuccess {
                            sortedValues = it
                            sortError = null
                        }
                        .onFailure {
                            sortError = it.message ?: "unknown error"
                        }
                },
            )

            CounterCard(counter = counter, onIncrement = { counter++ })

            Text(
                text = "Compose · JNI · AGP 9.4.1 · Kotlin 2.4.21",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/** 只在首次组合时读一次 native 信息，失败也不要把整个界面炸掉。 */
private fun loadNativeInfo(): String {
    if (!NativeBridge.isLoaded) {
        return "native 库加载失败：${NativeBridge.loadFailureMessage.orEmpty()}"
    }
    return runCatching {
        buildString {
            appendLine(NativeBridge.greeting())
            appendLine()
            append(NativeBridge.buildInfo())
        }
    }.getOrElse { "JNI 调用失败：${it.message.orEmpty()}" }
}

@Composable
private fun NativeInfoCard(info: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Native 库")
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SortCard(
    values: IntArray,
    sorted: List<Int>?,
    error: String?,
    onSort: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("C++ std::sort")
            KeyValue(key = "输入", value = values.toList().toDisplayString())
            KeyValue(key = "输出", value = sorted?.toDisplayString() ?: "点击下面的按钮")
            if (error != null) {
                Text(
                    text = "调用失败：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onSort) {
                Text("调用 C++ 排序")
            }
        }
    }
}

@Composable
private fun CounterCard(counter: Int, onIncrement: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionTitle("Compose 状态")
                Text(
                    text = "计数变化只触发局部重组",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onIncrement) {
                Text("+1 ($counter)")
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SortAppPreview() {
    SortTheme(dynamicColor = false) {
        SortApp()
    }
}
