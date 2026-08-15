package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.DiskPressureStatus
import com.m57.hermescontrol.data.model.MemoryPressureStatus
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.onColorFor

/**
 * Worst-first severity of the host-health advisory (mirrors the desktop
 * `MemoryPressureBanner` trigger order, issue #903): disk critical beats
 * memory critical (imminent data loss beats imminent restart), and a
 * suspected-OOM restart is a post-mortem that beats plain elevated states.
 */
enum class PressureTrigger {
    DISK_CRITICAL,
    MEMORY_CRITICAL,
    OOM_RESTART,
    DISK_ELEVATED,
    MEMORY_ELEVATED,
}

/**
 * Picks the advisory to surface from the `/api/status` memory/disk blocks.
 * Returns `null` when there is nothing to warn about. `unknown` pressure is
 * absence of evidence, not recovery — it never triggers (and never clears)
 * a banner, same as the desktop banner.
 */
internal fun selectPressureTrigger(
    memory: MemoryPressureStatus?,
    disk: DiskPressureStatus?,
): PressureTrigger? =
    when {
        disk?.pressure == "critical" -> PressureTrigger.DISK_CRITICAL
        memory?.pressure == "critical" -> PressureTrigger.MEMORY_CRITICAL
        memory?.last_boot_suspected_oom == true -> PressureTrigger.OOM_RESTART
        disk?.pressure == "elevated" -> PressureTrigger.DISK_ELEVATED
        memory?.pressure == "elevated" -> PressureTrigger.MEMORY_ELEVATED
        else -> null
    }

/**
 * Small advisory banner for host resource trouble (issue #903): renders when
 * the backend's `/api/status` reports memory/disk pressure, so an OOM-thrashing
 * or disk-full host explains itself instead of looking like a dead agent.
 * Advisory only — it never gates anything and never blocks the UI.
 */
@Composable
fun PressureBanner(
    memory: MemoryPressureStatus?,
    disk: DiskPressureStatus?,
    modifier: Modifier = Modifier,
) {
    val trigger = selectPressureTrigger(memory, disk) ?: return
    val statusColors = LocalHermesStatusColors.current
    val critical =
        trigger == PressureTrigger.DISK_CRITICAL ||
            trigger == PressureTrigger.MEMORY_CRITICAL
    val bgColor =
        if (critical) {
            statusColors.errorContainer
        } else {
            statusColors.warningContainer
        }
    val fgColor = onColorFor(bgColor)

    val message =
        when (trigger) {
            PressureTrigger.DISK_CRITICAL ->
                stringResource(R.string.pressure_banner_disk_critical) +
                    diskFreeSuffix(disk)

            PressureTrigger.DISK_ELEVATED ->
                stringResource(R.string.pressure_banner_disk_elevated) +
                    diskFreeSuffix(disk)

            PressureTrigger.MEMORY_CRITICAL ->
                stringResource(R.string.pressure_banner_memory_critical)

            PressureTrigger.OOM_RESTART ->
                stringResource(R.string.pressure_banner_memory_oom)

            PressureTrigger.MEMORY_ELEVATED ->
                stringResource(R.string.pressure_banner_memory_elevated)
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = bgColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = fgColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun diskFreeSuffix(disk: DiskPressureStatus?): String {
    val freeMb = disk?.free_mb ?: return ""
    return stringResource(R.string.pressure_banner_disk_free_suffix, freeMb)
}
