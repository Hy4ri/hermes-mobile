package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.ConnectionEnvField
import com.m57.hermescontrol.data.model.ConnectionOperationSnapshot
import com.m57.hermescontrol.data.model.ConnectionOperationTarget
import com.m57.hermescontrol.data.model.ConnectionTargetAction
import com.m57.hermescontrol.data.model.ConnectionTargetKind
import com.m57.hermescontrol.data.model.ConnectionTargetState
import com.m57.hermescontrol.ui.chat.ConnectionOperationUiState
import com.m57.hermescontrol.ui.chat.ConnectionPendingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ConnectionSetupSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun fieldsKeepBackendOrder_defaultsAreSafe_andRequiredInputGatesConnect() {
        var response: Triple<String, Map<String, String>, Boolean>? = null
        val target =
            target(
                requiredEnv =
                    listOf(
                        env("REGION", required = false, defaultValue = "us-east"),
                        env(
                            "API_TOKEN",
                            required = true,
                            secret = true,
                            defaultValue = "issue1218-secret-canary",
                        ),
                    ),
            )
        composeTestRule.setContent {
            ConnectionSetupContent(
                state = state(target),
                onRespond = { name, values, approved -> response = Triple(name, values, approved) },
                onContinue = {},
                onOpenBrowser = { _, _ -> },
            )
        }

        val region = composeTestRule.onNodeWithTag("connection_env_REGION")
        val token = composeTestRule.onNodeWithTag("connection_env_API_TOKEN")
        region.assertIsDisplayed().assertTextContains("us-east")
        token.assertIsDisplayed()
        composeTestRule.onNodeWithText("issue1218-secret-canary").assertDoesNotExist()
        assertTrue(region.fetchSemanticsNode().boundsInRoot.top < token.fetchSemanticsNode().boundsInRoot.top)
        composeTestRule.onNodeWithTag("connection_setup_connect").assertIsNotEnabled()

        token.performTextInput("typed-secret")
        composeTestRule.onNodeWithTag("connection_setup_connect").assertIsEnabled().performClick()

        assertEquals("github", response?.first)
        assertEquals(mapOf("REGION" to "us-east", "API_TOKEN" to "typed-secret"), response?.second)
        assertEquals(true, response?.third)
    }

    @Test
    fun pendingAction_disablesMutatingControls() {
        var calls = 0
        val operation = operation(target())
        composeTestRule.setContent {
            ConnectionSetupContent(
                state =
                    ConnectionOperationUiState(
                        operation = operation,
                        pendingAction = ConnectionPendingAction.Respond("op-a", "github", true, 1L),
                    ),
                onRespond = { _, _, _ -> calls++ },
                onContinue = { calls++ },
                onOpenBrowser = { _, _ -> calls++ },
            )
        }

        composeTestRule.onNodeWithTag("connection_setup_connect").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("connection_setup_skip").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("connection_setup_continue").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("connection_setup_pending").assertIsDisplayed()
        assertEquals(0, calls)
    }

    @Test
    fun browserAction_usesTheActiveUnresolvedTarget() {
        var opened: Pair<String, String>? = null
        val connected = target(name = "already-done", state = ConnectionTargetState.CONNECTED)
        val waiting =
            target(
                name = "slack",
                state = ConnectionTargetState.INITIATED,
                connectUrl = "https://example.com/oauth?state=abc",
            )
        composeTestRule.setContent {
            ConnectionSetupContent(
                state = ConnectionOperationUiState(operation = operation(connected, waiting)),
                onRespond = { _, _, _ -> },
                onContinue = {},
                onOpenBrowser = { operationId, url -> opened = operationId to url },
            )
        }

        composeTestRule.onNodeWithText("Set up slack").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_setup_open_browser").performClick()
        assertEquals("op-a" to "https://example.com/oauth?state=abc", opened)
    }

    @Test
    fun unsupportedTarget_degradesToSafeSkipAndContinueActions() {
        var skipped = false
        var continued = false
        composeTestRule.setContent {
            ConnectionSetupContent(
                state = state(target(state = ConnectionTargetState.UNKNOWN)),
                onRespond = { _, _, approved -> skipped = !approved },
                onContinue = { continued = true },
                onOpenBrowser = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("This setup step is not supported by this app version.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_setup_skip").performClick()
        composeTestRule.onNodeWithTag("connection_setup_continue").performClick()
        assertTrue(skipped)
        assertTrue(continued)
    }

    @Test
    fun authorizedWithoutTools_showsDiscoveryErrorAndContinueOnly() {
        composeTestRule.setContent {
            ConnectionSetupContent(
                state =
                    state(
                        target(
                            state = ConnectionTargetState.CONNECTED,
                            discoveryError = "No compatible tools were discovered",
                        ),
                    ),
                onRespond = { _, _, _ -> },
                onContinue = {},
                onOpenBrowser = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Authorized; tools unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("No compatible tools were discovered").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_setup_skip").assertDoesNotExist()
        composeTestRule.onNodeWithTag("connection_setup_continue").assertIsDisplayed()
    }

    @Test
    fun discoveryError_doesNotHideALaterPendingTarget() {
        composeTestRule.setContent {
            ConnectionSetupContent(
                state =
                    ConnectionOperationUiState(
                        operation =
                            operation(
                                target(
                                    name = "github",
                                    state = ConnectionTargetState.CONNECTED,
                                    discoveryError = "No compatible tools were discovered",
                                ),
                                target(name = "slack"),
                            ),
                    ),
                onRespond = { _, _, _ -> },
                onContinue = {},
                onOpenBrowser = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Set up slack").assertIsDisplayed()
        composeTestRule.onNodeWithText("Authorized; tools unavailable").assertDoesNotExist()
    }

    @Test
    fun resolvedTarget_canStillSettleTheOperation() {
        composeTestRule.setContent {
            ConnectionSetupContent(
                state =
                    state(
                        target(
                            state = ConnectionTargetState.CONNECTED,
                            tools = listOf("search", "fetch"),
                        ),
                    ),
                onRespond = { _, _, _ -> },
                onContinue = {},
                onOpenBrowser = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("This connection step is complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tools available: 2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_setup_continue").assertIsDisplayed()
        composeTestRule.onNodeWithTag("connection_setup_skip").assertDoesNotExist()
    }

    private fun state(target: ConnectionOperationTarget): ConnectionOperationUiState =
        ConnectionOperationUiState(operation = operation(target))

    private fun operation(vararg targets: ConnectionOperationTarget): ConnectionOperationSnapshot =
        ConnectionOperationSnapshot(
            sessionId = "session-a",
            opId = "op-a",
            seq = 1L,
            deadlineAt = 2_000_000_000.0,
            timeoutSeconds = 300.0,
            toolCallId = "tool-a",
            settled = false,
            settledBy = null,
            targets = targets.toList(),
        )

    private fun target(
        name: String = "github",
        state: ConnectionTargetState = ConnectionTargetState.PENDING,
        connectUrl: String? = null,
        discoveryError: String? = null,
        requiredEnv: List<ConnectionEnvField> = emptyList(),
        tools: List<String> = emptyList(),
    ): ConnectionOperationTarget =
        ConnectionOperationTarget(
            name = name,
            kind = ConnectionTargetKind.CONNECTOR,
            action = ConnectionTargetAction.AUTHORIZE,
            state = state,
            detail = null,
            instructions = null,
            discoveryError = discoveryError,
            connectUrl = connectUrl,
            connectionId = null,
            attempt = null,
            requiredEnv = requiredEnv,
            tools = tools,
            hint = null,
        )

    private fun env(
        name: String,
        required: Boolean,
        secret: Boolean = false,
        defaultValue: String? = null,
    ): ConnectionEnvField =
        ConnectionEnvField(
            name = name,
            required = required,
            secret = secret,
            defaultValue = defaultValue,
            prompt = null,
        )
}
