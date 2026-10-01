package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class MarkdownTableRenderingTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tableHeaderAndCellRenderInlineMarkup() {
        composeTestRule.setContent {
            MaterialTheme {
                MarkdownText(
                    text = "| **Name** | Value |\n| --- | --- |\n| Item | `code` |",
                    textColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        composeTestRule.onNodeWithText("Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("code").assertIsDisplayed()
        composeTestRule.onNodeWithText("**Name**").assertDoesNotExist()
        composeTestRule.onNodeWithText("`code`").assertDoesNotExist()
    }

    @Test
    fun columnsSizeToContentAndStayAlignedAcrossRows() {
        composeTestRule.setContent {
            MaterialTheme {
                Box(Modifier.width(220.dp)) {
                    MarkdownText(
                        text = "| ID | Description |\n| --- | --- |\n| A | A considerably longer table value |",
                        textColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        val idHeader = composeTestRule.onNodeWithTag("markdown_table_header_0").getUnclippedBoundsInRoot()
        val descHeader = composeTestRule.onNodeWithTag("markdown_table_header_1").getUnclippedBoundsInRoot()
        val idCell = composeTestRule.onNodeWithTag("markdown_table_cell_0_0").getUnclippedBoundsInRoot()
        val descCell = composeTestRule.onNodeWithTag("markdown_table_cell_0_1").getUnclippedBoundsInRoot()
        assertTrue(descHeader.right - descHeader.left > idHeader.right - idHeader.left)
        assertEquals(idHeader.right - idHeader.left, idCell.right - idCell.left)
        assertEquals(descHeader.right - descHeader.left, descCell.right - descCell.left)
    }
}
