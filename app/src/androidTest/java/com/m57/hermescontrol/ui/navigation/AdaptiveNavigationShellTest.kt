package com.m57.hermescontrol.ui.navigation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.m57.hermescontrol.AdaptiveNavigationShell
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Issues #1639 / #1640: the closed modal drawer must not draw over the wide-window sidebar. */
@RunWith(AndroidJUnit4::class)
class AdaptiveNavigationShellTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun wideWindowShowsOnlyTheSidebarCopyFullyOnScreen() {
        compose.setContent {
            HermesControlTheme {
                AdaptiveNavigationShell(
                    wideWindow = true,
                    sidebarVisible = true,
                    drawerState = rememberDrawerState(DrawerValue.Closed),
                    gesturesEnabled = true,
                    drawerBody = {
                        // fillMaxWidth like NavigationDrawerItem: the modal sheet grows to its max width.
                        Text(
                            text = "Chat",
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp).testTag(ITEM),
                        )
                    },
                    onModalItemClick = {},
                    content = {},
                )
            }
        }

        // boundsInRoot is clipped: a node hidden by a parent clip has an empty rect.
        val visible =
            compose
                .onAllNodesWithTag(ITEM)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .filter { it.width > 0f && it.height > 0f }
        assertEquals("only the sidebar copy may be visible: $visible", 1, visible.size)
        assertTrue("sidebar item starts off-screen: ${visible.single()}", visible.single().left >= 0f)
    }

    private companion object {
        const val ITEM = "drawer-item"
    }
}
