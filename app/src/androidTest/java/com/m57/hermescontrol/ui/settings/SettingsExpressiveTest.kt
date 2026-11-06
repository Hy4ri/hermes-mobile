package com.m57.hermescontrol.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsExpressiveTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun segmentedRowsKeepTheirOwnAccessibleClickTargets() {
        val clicks = mutableListOf<Int>()
        compose.setContent {
            HermesControlTheme {
                SettingsCategoryCard(
                    (0..2).map { index ->
                        SettingsRow(
                            icon = Icons.Filled.Palette,
                            label = "Category $index",
                            summary = "Current setting $index",
                            onClick = { clicks += index },
                        )
                    },
                )
            }
        }
        (0..2).forEach { index ->
            compose
                .onNodeWithText("Category $index")
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }
        compose.runOnIdle { assertEquals(listOf(0, 1, 2), clicks) }
    }

    @Test
    fun profileLockStillHidesConnectionAndVaultDestinations() {
        compose.setContent {
            HermesControlTheme {
                val unlocked = settingsRows(SettingsUiState(), profileLocked = false)
                val locked = settingsRows(SettingsUiState(), profileLocked = true)
                // Keep this check tied to the production row builder, not a preview fixture.
                assertEquals(7, unlocked.size)
                assertEquals(
                    unlocked
                        .filterIndexed { index, _ -> index != 0 && index != 5 }
                        .map { it.label },
                    locked.map { it.label },
                )
                SettingsCategoryCard(locked)
            }
        }
        compose.waitForIdle()
    }
}
