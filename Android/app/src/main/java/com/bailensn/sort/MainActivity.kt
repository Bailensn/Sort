package com.bailensn.sort

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bailensn.sort.ui.table.TableScreen
import com.bailensn.sort.ui.theme.SortTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SortTheme {
                TableScreen()
            }
        }
    }
}
