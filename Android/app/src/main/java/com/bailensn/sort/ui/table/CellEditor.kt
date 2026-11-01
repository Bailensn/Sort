package com.bailensn.sort.ui.table

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bailensn.sort.R
import com.bailensn.sort.data.NameMemory
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class CellEdit(val row: Int, val column: Int, val value: String)

@Composable
internal fun CellEditSheet(
    cell: CellEdit,
    names: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(cell) { mutableStateOf(cell.value) }
    val focusRequester = remember { FocusRequester() }
    // 只在开始输入之后才给候选，而且按已输入的字模糊筛，不整份名单糊上来。
    val matches = remember(text, names) { NameMemory.search(text, names) }

    OverlayBottomSheet(
        show = true,
        title = stringResource(R.string.edit_title, cell.row + 1, cell.column + 1),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 底部弹层只补了 IME 内边距，手势条会压住按钮，这里自己补，再留一点呼吸。
                .navigationBarsPadding()
                .padding(top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                singleLine = true,
            )
            if (matches.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.edit_suggestions),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    matches.forEach { name ->
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

    // 打开就聚焦：手不用先点一下输入框，键盘也会立即把弹层顶起来。
    LaunchedEffect(cell) {
        // 等进场那一帧，弹层还没量完的时候 requestFocus 打不中。
        withFrameNanos { }
        focusRequester.requestFocus()
    }
}
