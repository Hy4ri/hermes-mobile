package com.m57.hermescontrol.ui.model.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ModelPickerDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun providers_startCollapsed_andOnlyOneProviderExpands() {
        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers =
                        listOf(
                            ModelProvider(slug = "openai", name = "OpenAI", models = listOf("gpt-4")),
                            ModelProvider(slug = "anthropic", name = "Anthropic", models = listOf("claude-3")),
                        ),
                    title = "Choose model",
                    onSelect = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("gpt-4").assertDoesNotExist()
        composeTestRule.onNodeWithText("claude-3").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("1 model").assertCountEquals(2)
        composeTestRule.onNodeWithContentDescription("Expand OpenAI models").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand Anthropic models").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Expand OpenAI models").performClick()
        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Collapse OpenAI models").assertIsDisplayed()
        composeTestRule.onNodeWithText("claude-3").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Expand Anthropic models").performClick()
        composeTestRule.onNodeWithText("gpt-4").assertDoesNotExist()
        composeTestRule.onNodeWithText("claude-3").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Collapse Anthropic models").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Collapse Anthropic models").performClick()
        composeTestRule.onNodeWithText("claude-3").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand Anthropic models").assertIsDisplayed()
    }

    @Test
    fun search_filtersModelsAndCountsMatches_beforeSelection() {
        var selectedProvider = ""
        var selectedModel = ""

        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers =
                        listOf(
                            ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-4", "embedding-3"),
                            ),
                            ModelProvider(slug = "anthropic", name = "Anthropic", models = listOf("claude-3")),
                        ),
                    title = "Choose model",
                    onSelect = { provider, model ->
                        selectedProvider = provider
                        selectedModel = model
                    },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("2 models").assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("gpt-4")
        composeTestRule.onNodeWithText("1 model").assertIsDisplayed()
        composeTestRule.onNodeWithText("embedding-3").assertDoesNotExist()
        composeTestRule.onAllNodesWithText("gpt-4").assertCountEquals(1)

        composeTestRule.onNodeWithContentDescription("Expand OpenAI models").performClick()
        composeTestRule.onAllNodesWithText("gpt-4").get(1).performClick()

        assertEquals("openai", selectedProvider)
        assertEquals("gpt-4", selectedModel)
    }

    @Test
    fun pinnedModels_remainVisibleAndPreserveCapabilitiesAndCallbacks() {
        var toggledProvider = ""
        var toggledModel = ""
        var selectedProvider = ""
        var selectedModel = ""

        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers =
                        listOf(
                            ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-4"),
                                capabilities = mapOf("gpt-4" to ModelCapabilities(reasoning = false)),
                            ),
                        ),
                    title = "Choose model",
                    pinnedModels = listOf(PinnedModel(providerSlug = "openai", modelName = "gpt-4")),
                    onPinToggle = { provider, model ->
                        toggledProvider = provider
                        toggledModel = model
                    },
                    onSelect = { provider, model ->
                        selectedProvider = provider
                        selectedModel = model
                    },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
        composeTestRule.onNodeWithText("no reasoning").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Unpin model").performClick()
        composeTestRule.onNodeWithText("gpt-4").performClick()

        assertEquals("openai", toggledProvider)
        assertEquals("gpt-4", toggledModel)
        assertEquals("openai", selectedProvider)
        assertEquals("gpt-4", selectedModel)
    }
}
