package com.m57.hermescontrol.ui.skills.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.m57.hermescontrol.ui.common.StatusBadge
import com.m57.hermescontrol.ui.common.StatusBadgeType

@Composable
internal fun SourceBadge(
    source: String,
    modifier: Modifier = Modifier,
) {
    val (status, label) =
        when (source) {
            "hub" -> StatusBadgeType.INFO to "hub"
            "built-in" -> StatusBadgeType.NEUTRAL to "built-in"
            "optional" -> StatusBadgeType.NEUTRAL to "opt"
            else -> StatusBadgeType.NEUTRAL to source
        }
    StatusBadge(
        text = label,
        status = status,
        modifier = modifier,
    )
}
