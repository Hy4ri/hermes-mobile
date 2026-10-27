package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ClarifyUi
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClarifyDictationLeaseUiTest {
    @get:Rule
    val compose = createComposeRule()

    /** Accepts launches and never delivers a result: the recognizer stays open. */
    private class HeldRegistry : ActivityResultRegistry() {
        var launches = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launches++
        }
    }

    @Before
    fun setUp() {
        ExternalActivityLifecycleGuard.resetForTest()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    @After
    fun tearDown() {
        ExternalActivityLifecycleGuard.resetForTest()
    }

    @Test
    fun cardLeavingCompositionWhileRecognizerIsOpen_releasesTheLease() {
        val registry = HeldRegistry()
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry: ActivityResultRegistry = registry
            }
        var showCard by mutableStateOf(true)

        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                HermesControlTheme {
                    if (showCard) {
                        ClarifyBubble(
                            clarifyRequest =
                                ClarifyUi(
                                    text = "Which database should the migration run against?",
                                    options = listOf("Staging", "Production"),
                                    clarifyId = "req-lease",
                                ),
                            onRespondSingle = {},
                            onRespondBatch = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("clarify_dictate_button").performClick()
        compose.runOnIdle {
            assertTrue("recognizer launched", registry.launches == 1)
            assertTrue(
                "lease held while the recognizer is open",
                ExternalActivityLifecycleGuard.isExternalActivityInFlightForTest(),
            )
            showCard = false
        }
        compose.runOnIdle {
            assertFalse(
                "lease released when the card leaves composition",
                ExternalActivityLifecycleGuard.isExternalActivityInFlightForTest(),
            )
        }
    }
}
