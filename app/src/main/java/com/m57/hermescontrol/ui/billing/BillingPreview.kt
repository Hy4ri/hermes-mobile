package com.m57.hermescontrol.ui.billing

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.SubscriptionCurrent
import com.m57.hermescontrol.data.model.SubscriptionTier
import com.m57.hermescontrol.theme.HermesControlTheme

/** Free plan with a cycle date (no Renews line) next to a paid plan (localized Renews date). */
@Preview(name = "Light", widthDp = 360, heightDp = 360)
@Preview(name = "Dark", widthDp = 360, heightDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Narrow large text", widthDp = 320, heightDp = 460, fontScale = 1.5f)
@Composable
private fun PlanCardPreview() {
    val tiers =
        listOf(
            SubscriptionTier(tier_id = "cmnie0xix000bjp049kwwgwgz", name = "Free", dollars_per_month_display = "$0"),
            SubscriptionTier(tier_id = "usage-window-hermes-plus", name = "Plus", dollars_per_month_display = "$20"),
        )
    HermesControlTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlanCard(
                    tiers = tiers,
                    subscription =
                        SubscriptionCurrent(
                            tier_id = "cmnie0xix000bjp049kwwgwgz",
                            tier_name = "Free",
                            credits_remaining = "0",
                            cycle_ends_at = "2026-12-03T17:54:44.000Z",
                        ),
                )
                PlanCard(
                    tiers = tiers,
                    subscription =
                        SubscriptionCurrent(
                            tier_id = "usage-window-hermes-plus",
                            tier_name = "Plus",
                            credits_remaining = "12.5",
                            cycle_ends_at = "2026-12-03T17:54:44.000Z",
                        ),
                )
            }
        }
    }
}
