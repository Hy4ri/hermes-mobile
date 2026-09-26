package com.m57.hermescontrol.ui.mcp

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.LocalSpacing
import com.m57.hermescontrol.ui.mcp.components.ServerCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class McpOwnershipCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pluginRowExplainsOwnershipAndDisablesMutations() {
        showServer("plugin", "security")
        composeRule.onNodeWithText("From plugin security · Read-only", useUnmergedTree = true).assertExists()
        composeRule.onNode(isToggleable()).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("Authorize").assertIsNotEnabled()
        composeRule.onNodeWithText("Test").assertIsEnabled()
        composeRule.onNodeWithText("Env vars").performClick()
        composeRule.onNodeWithContentDescription("Remove").assertIsNotEnabled()
        composeRule.onNodeWithText("Add env var").assertIsNotEnabled()
        composeRule.onNodeWithText("KEY", useUnmergedTree = true).assertExists()
    }

    @Test
    fun configRowKeepsMutationsEnabled() {
        showServer("config", null)
        composeRule.onNode(isToggleable()).assertIsEnabled()
        composeRule.onNodeWithContentDescription("Delete").assertIsEnabled()
        composeRule.onNodeWithText("Authorize").assertIsEnabled()
        composeRule.onNodeWithText("Env vars").performClick()
        composeRule.onNodeWithContentDescription("Remove").assertIsEnabled()
        composeRule.onNodeWithText("Add env var").assertIsEnabled()
    }

    private fun showServer(
        source: String,
        plugin: String?,
    ) {
        val server =
            McpServer(
                name = "scanner",
                enabled = true,
                source = source,
                plugin = plugin,
                auth = "oauth",
                env = mapOf("KEY" to "test-value"),
            )
        val viewModel = McpServersViewModel()
        composeRule.setContent {
            HermesControlTheme {
                ServerCard(
                    server = server,
                    state = McpServersUiState(servers = listOf(server)),
                    viewModel = viewModel,
                    spacing = LocalSpacing.current,
                    onClick = {},
                    onOpenBrowser = {},
                )
            }
        }
    }
}
