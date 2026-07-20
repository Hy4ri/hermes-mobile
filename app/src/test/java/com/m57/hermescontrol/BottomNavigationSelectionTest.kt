package com.m57.hermescontrol

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that the bottom-nav selection key maps Settings drill-down
 * destinations back to [SettingsScreen] so the indicator stays highlighted,
 * while every other screen is returned unchanged.
 */
class BottomNavigationSelectionTest {
    @Test
    fun `SettingsScreen selects itself`() {
        assertEquals(SettingsScreen, selectedBottomNavDestination(SettingsScreen))
    }

    @Test
    fun `every Settings drill-down maps back to SettingsScreen`() {
        val drillDowns: List<NavKey> =
            listOf(
                SettingsConnection,
                SettingsAppearance,
                SettingsChat,
                SettingsNavBar,
                SettingsBehavior,
                SettingsAbout,
            )
        for (destination in drillDowns) {
            assertEquals(
                "Expected $destination to map to SettingsScreen",
                SettingsScreen,
                selectedBottomNavDestination(destination),
            )
        }
    }

    @Test
    fun `non-Settings screens are returned unchanged`() {
        val others: List<NavKey> =
            listOf(
                ChatScreen,
                CronJobsScreen,
                WebhooksScreen,
                GatewayScreen,
                SkillsScreen,
                LandingScreen,
                AuthLoginScreen,
            )
        for (screen in others) {
            assertEquals(
                "Expected $screen to be returned unchanged",
                screen,
                selectedBottomNavDestination(screen),
            )
        }
    }
}
