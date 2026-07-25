package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Backend liveness probe, mirrored from `GET /api/health` (hermes_cli/web_server.py).
 *
 * A lightweight alternative to `/api/status` — no gateway PID / remote-health
 * probe, no profile scope. `authRequired` tells the client which auth scheme the
 * gateway expects (gated/OAuth vs loopback token) before attempting a real call.
 */
@Serializable
data class HealthStatus(
    val ok: Boolean = false,
    val version: String? = null,
    val authRequired: Boolean? = null,
)
