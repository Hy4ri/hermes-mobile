package com.m57.hermescontrol.ui.model

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ModelScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockViewModel = mockk<ModelViewModel>(relaxed = true)
    private val uiStateFlow = MutableStateFlow(ModelUiState())

    @Before
    fun setUp() {
        every { mockViewModel.uiState } returns uiStateFlow.asStateFlow()
    }

    @Test
    fun modelScreen_pinnedSectionHidden_whenNoPinnedModels() {
        uiStateFlow.value =
            ModelUiState(
                providers =
                    listOf(
                        ModelProvider(
                            slug = "openai",
                            name = "OpenAI",
                            is_current = true,
                            is_user_defined = false,
                            models = listOf("gpt-4"),
                            total_models = 1,
                            source = null,
                            authenticated = true,
                            auth_type = null,
                            warning = null,
                        ),
                    ),
                pinnedModels = emptyList(),
            )

        composeTestRule.setContent {
            ModelScreen(
                onOpenDrawer = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Pinned Models").assertDoesNotExist()
    }

    @Test
    fun modelScreen_rendersPinnedModels_whenPresent() {
        uiStateFlow.value =
            ModelUiState(
                providers =
                    listOf(
                        ModelProvider(
                            slug = "openai",
                            name = "OpenAI",
                            is_current = true,
                            is_user_defined = false,
                            models = listOf("gpt-4"),
                            total_models = 1,
                            source = null,
                            authenticated = true,
                            auth_type = null,
                            warning = null,
                        ),
                    ),
                pinnedModels =
                    listOf(
                        PinnedModel(providerSlug = "openai", modelName = "gpt-4"),
                    ),
            )

        composeTestRule.setContent {
            ModelScreen(
                onOpenDrawer = {},
                viewModel = mockViewModel,
            )
        }

        composeTestRule.onNodeWithText("Pinned Models").assertIsDisplayed()
        composeTestRule.onNodeWithText("gpt-4").assertIsDisplayed()
    }
}
