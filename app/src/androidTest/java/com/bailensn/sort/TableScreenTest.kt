package com.bailensn.sort

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仪器测试需要设备/模拟器才会执行；CI 里只编译它（assembleDebugAndroidTest），
 * 想真跑的话加一个 emulator-runner job 或本地 `./gradlew connectedDebugAndroidTest`。
 */
@RunWith(AndroidJUnit4::class)
class TableScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsTitle() {
        composeRule.onNodeWithText("Sort").assertIsDisplayed()
    }
}
