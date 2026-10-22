package com.m57.hermescontrol.ui.settings

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsBehaviorPageTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun kanbanAlertsTogglePersistsBothStates() = verifyToggle(LayoutDirection.Ltr)

    @Test
    fun kanbanAlertsTogglePersistsInRtl() = verifyToggle(LayoutDirection.Rtl)

    private fun verifyToggle(layoutDirection: LayoutDirection) {
        val initial = AuthManager.isNotifyKanbanAlerts()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val label = hasContentDescription(context.getString(R.string.settings_notify_kanban_alerts_title))
        val toggle: SemanticsMatcher = isToggleable() and label
        AuthManager.setNotifyKanbanAlerts(true)
        try {
            composeTestRule.setContent {
                HermesControlTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                        SettingsBehaviorPage(onBack = {})
                    }
                }
            }
            composeTestRule.onNode(toggle).performScrollTo().assertIsOn()
            composeTestRule.waitForIdle()
            capture("kanban-alerts-${layoutDirection.name}-enabled.png")

            composeTestRule.onNode(toggle).performClick().assertIsOff()
            composeTestRule.runOnIdle { assertFalse(AuthManager.isNotifyKanbanAlerts()) }
            capture("kanban-alerts-${layoutDirection.name}-disabled.png")

            composeTestRule.onNode(toggle).performClick().assertIsOn()
            composeTestRule.runOnIdle { assertTrue(AuthManager.isNotifyKanbanAlerts()) }
        } finally {
            AuthManager.setNotifyKanbanAlerts(initial)
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
