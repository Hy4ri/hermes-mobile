package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Host battery read-out, mirrored from the backend's `system.battery` WS method
 * (agent.battery.read_battery on the *backend host* — not the phone).
 *
 * `available: false` means the backend host has no battery (desktop/server/VM)
 * or the read failed. The UI must treat the whole payload as "render nothing"
 * in that case.
 */
@Serializable
data class BatteryStatus(
    val available: Boolean = false,
    val percent: Double? = null,
    val plugged: Boolean? = null,
    val category: String? = null,
)
