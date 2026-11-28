package com.m57.hermescontrol.ui.achievements

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme

/** Static fixture of the achievements header area: stats, scan status and filter chips. */
@Preview(name = "Light", widthDp = 360, heightDp = 420)
@Preview(name = "Dark", widthDp = 360, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 320, heightDp = 520, fontScale = 1.5f)
@Preview(name = "Narrow Arabic large text", widthDp = 320, heightDp = 520, fontScale = 1.5f, locale = "ar")
@Composable
private fun AchievementsHeaderPreview() {
    HermesControlTheme {
        Surface {
            Column(
                modifier = Modifier.padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val inset = Modifier.padding(horizontal = 16.dp)
                StatsRow(modifier = inset, unlockedCount = 56, discoveredCount = 4, secretCount = 0, totalCount = 60)
                ScanStatusChip(
                    modifier = inset,
                    scanState = "idle",
                    isStale = false,
                    scanLastError = null,
                    scanRunCount = 1,
                    isRescanning = false,
                )
                CategoryFilterRow(
                    categories = listOf("Agent Autonomy", "Debugging Chaos", "Tool Mastery"),
                    activeCategory = "Debugging Chaos",
                    onCategorySelected = {},
                )
                StateFilterRow(activeState = "secret", onStateSelected = {})
            }
        }
    }
}
