package com.bailensn.sort.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.bailensn.sort.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 首启的隐私说明：必须读满 5 秒才能点同意。 */
@Composable
fun PrivacyGate(onAccept: () -> Unit) {
    var remaining by remember { mutableStateOf(5) }
    LaunchedEffect(Unit) {
        repeat(5) {
            delay(1000)
            remaining -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.privacy_title),
                color = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.privacy_body),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (remaining == 0) onAccept() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (remaining > 0) {
                        stringResource(R.string.privacy_wait, remaining)
                    } else {
                        stringResource(R.string.privacy_accept)
                    },
                )
            }
        }
    }
}
