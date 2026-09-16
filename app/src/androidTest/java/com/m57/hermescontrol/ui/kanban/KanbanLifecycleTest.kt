package com.m57.hermescontrol.ui.kanban

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KanbanLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pendingExportSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        lateinit var path: MutableState<String?>
        restoration.setContent {
            path = rememberPendingKanbanExportPath()
        }
        compose.runOnIdle { path.value = "/cache/board.tar.gz" }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals("/cache/board.tar.gz", path.value) }
    }

    @Test
    fun tickerStopsInBackgroundRefreshesOnResumeAndStopsOnDisposal() {
        lateinit var registry: LifecycleRegistry
        val owner =
            object : LifecycleOwner {
                override val lifecycle: Lifecycle get() = registry
            }
        compose.runOnUiThread {
            registry = LifecycleRegistry(owner)
            registry.currentState = Lifecycle.State.STARTED
        }
        var now = 100L
        val visible = mutableStateOf(true)
        var displayed = 0L
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (visible.value) {
                    displayed = rememberKanbanNowSeconds(true) { now }
                    Text("$displayed")
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("100").assertTextEquals("100")
        compose.runOnUiThread { now = 105L }
        compose.mainClock.advanceTimeBy(5_100)
        compose.onNodeWithText("105").assertTextEquals("105")
        compose.runOnUiThread {
            registry.currentState = Lifecycle.State.CREATED
            now = 200L
        }
        compose.mainClock.advanceTimeBy(10_100)
        compose.onNodeWithText("105").assertTextEquals("105")
        compose.runOnUiThread { registry.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("200").assertTextEquals("200")
        compose.runOnUiThread { visible.value = false }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { now = 300L }
        compose.mainClock.advanceTimeBy(10_100)
        compose.runOnIdle { assertEquals(200L, displayed) }
    }
}
