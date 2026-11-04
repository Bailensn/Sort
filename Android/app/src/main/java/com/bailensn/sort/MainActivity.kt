package com.bailensn.sort

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bailensn.sort.ui.privacy.PrivacyGate
import com.bailensn.sort.ui.privacy.PrivacyStore
import com.bailensn.sort.ui.table.TableScreen
import com.bailensn.sort.ui.theme.SortTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SortTheme {
                var accepted by remember { mutableStateOf(PrivacyStore.accepted(this@MainActivity)) }
                if (accepted) {
                    TableScreen()
                } else {
                    PrivacyGate(
                        onAccept = {
                            PrivacyStore.accept(this@MainActivity)
                            accepted = true
                        },
                    )
                }
            }
        }
    }
}
