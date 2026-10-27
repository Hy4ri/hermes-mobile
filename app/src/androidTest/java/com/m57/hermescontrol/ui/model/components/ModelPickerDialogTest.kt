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
    fun pinnedModels_areSelectableWhileCatalogLoads() {
        var selectedModel = ""
        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers = emptyList(),
                    title = "Choose model",
                    isLoading = true,
                    pinnedModels = listOf(PinnedModel("openai", "gpt-4")),
                    onSelect = { _, model -> selectedModel = model },
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed().performClick()
        assertEquals("gpt-4", selectedModel)
    }

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

    @Test
    fun pinnedRows_showProvider_soDuplicateModelNamesAreDistinguishable() {
        var selectedProvider = ""
        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers =
                        listOf(
                            ModelProvider(slug = "alpha", name = "Alpha", models = listOf("deepseek-v4.1-flash")),
                            ModelProvider(slug = "beta", name = "Beta", models = listOf("deepseek-v4.1-flash")),
                        ),
                    title = "Choose model",
                    pinnedModels =
                        listOf(
                            PinnedModel("alpha", "deepseek-v4.1-flash"),
                            PinnedModel("beta", "deepseek-v4.1-flash"),
                        ),
                    onSelect = { provider, _ -> selectedProvider = provider },
                    onDismiss = {},
                )
            }
        }

        // Two providers ship the same model name; the pinned rows stay
        // distinguishable because each carries its provider (#1697).
        composeTestRule.onAllNodesWithText("deepseek-v4.1-flash").assertCountEquals(2)
        // Provider text appears on the collapsed section header and on the
        // pinned row itself — without the label there is only the header.
        composeTestRule.onAllNodesWithText("Alpha").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Beta").assertCountEquals(2)

        composeTestRule.onAllNodesWithText("deepseek-v4.1-flash")[0].performClick()
        assertEquals("alpha", selectedProvider)
    }

    @Test
    fun groupedRows_doNotRepeatTheProviderLabel() {
        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers = listOf(ModelProvider(slug = "openai", name = "OpenAI", models = listOf("gpt-4"))),
                    title = "Choose model",
                    pinnedModels = listOf(PinnedModel("openai", "gpt-4")),
                    onSelect = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand OpenAI models").performClick()

        // The provider shows on the header and the pinned row; the grouped row
        // leans on its header and does not repeat it.
        composeTestRule.onAllNodesWithText("OpenAI").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("gpt-4").assertCountEquals(2)
    }

    @Test
    fun pinnedRow_showsSlugWhileCatalogLoads() {
        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers = emptyList(),
                    title = "Choose model",
                    isLoading = true,
                    pinnedModels = listOf(PinnedModel("openai", "gpt-4")),
                    onSelect = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
        composeTestRule.onNodeWithText("openai").assertIsDisplayed()
    }

    @Test
    fun pinnedSearch_matchesProviderDisplayName() {
        composeTestRule.setContent {
            HermesControlTheme {
                ModelPickerDialog(
                    providers = listOf(ModelProvider(slug = "nous", name = "Nous Portal", models = emptyList())),
                    title = "Choose model",
                    pinnedModels = listOf(PinnedModel("nous", "gpt-4")),
                    onSelect = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        // The provider label is searchable, not just decorative: the model name
        // does not contain the query, so only the label keeps the row visible.
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Portal")
        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
    }
}
