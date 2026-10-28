package com.bailensn.sort.ui.table

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.bailensn.sort.R
import com.bailensn.sort.data.HistoryStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun HistoryPage(
    entries: List<HistoryStore.Entry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpen: (HistoryStore.Entry) -> Unit,
    onDelete: (List<String>) -> Unit,
) {
    val allSelected = entries.isNotEmpty() && selected.size == entries.size
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (entries.isEmpty()) {
            item("empty") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.history_empty),
                        summary = stringResource(R.string.history_subtitle),
                    )
                }
            }
            return@LazyColumn
        }

        item("title") { SmallTitle(stringResource(R.string.history_title)) }

        if (selected.isNotEmpty()) {
            item("selection") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = stringResource(R.string.history_selected, selected.size),
                        summary = stringResource(R.string.history_subtitle),
                        endActions = {
                            TextButton(
                                text = stringResource(R.string.action_delete),
                                onClick = { onDelete(selected.toList()) },
                            )
                        },
                    )
                }
            }
        }

        item("entries") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                entries.forEach { entry ->
                    BasicComponent(
                        title = HistoryStore.formatTime(entry.timestamp),
                        summary = stringResource(
                            R.string.preview_meta,
                            entry.rowCount,
                            entry.columnCount,
                        ),
                        startAction = {
                            Checkbox(
                                state = if (entry.id in selected) {
                                    ToggleableState.On
                                } else {
                                    ToggleableState.Off
                                },
                                onClick = { onToggle(entry.id) },
                            )
                        },
                        onClick = { onOpen(entry) },
                    )
                }
            }
        }

        item("select-all") {
            TextButton(
                text = stringResource(
                    if (allSelected) R.string.action_clear_selection else R.string.action_select_all,
                ),
                onClick = if (allSelected) onClearSelection else onSelectAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
        }
    }
}
