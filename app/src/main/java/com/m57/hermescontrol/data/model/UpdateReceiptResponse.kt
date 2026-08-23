package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Durable record of the most recent `hermes update` run, returned by
 * `GET /api/hermes/update/receipt` (hermes-agent #91277). This is the
 * authoritative outcome — it survives gateway restarts and log rotation,
 * unlike heuristic connection-retry polling.
 *
 * All fields are nullable: the backend may emit a partial receipt, and the
 * response is decoded with `ignoreUnknownKeys = true`, so future additions
 * won't break parsing.
 *
 * The same data is also attached (flattened) to
 * `GET /api/actions/hermes-update/status` under a `receipt` key.
 */
@Serializable
data class UpdateReceiptResponse(
    val receipt: UpdateReceipt? = null,
    val summary: UpdateReceiptSummary? = null,
)

@Serializable
data class UpdateReceipt(
    val schema: Int? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    val argv: List<String>? = null,
    val pid: Int? = null,
    /** `success` | `partial` | `running`. `running` means the update is still mid-flight. */
    val outcome: String? = null,
    val preUpdate: UpdateReceiptVersion? = null,
    val postUpdate: UpdateReceiptVersion? = null,
    val steps: List<UpdateReceiptStep>? = null,
    val skips: List<String>? = null,
    val gatewayRestart: UpdateReceiptGatewayRestart? = null,
    val fleet: List<UpdateReceiptFleetEntry>? = null,
)

@Serializable
data class UpdateReceiptVersion(
    val sha: String? = null,
    val version: String? = null,
)

@Serializable
data class UpdateReceiptStep(
    val name: String? = null,
    val ok: Boolean? = null,
    val detail: String? = null,
    val at: String? = null,
)

@Serializable
data class UpdateReceiptGatewayRestart(
    val at: String? = null,
    val ok: Boolean? = null,
)

@Serializable
data class UpdateReceiptFleetEntry(
    val profile: String? = null,
    val pid: Int? = null,
    val codeSha: String? = null,
    val state: String? = null,
)

/**
 * Flattened highlights of [UpdateReceipt], returned alongside it by the
 * endpoint. Mirrors the backend's `summary` object so the popup can show the
 * essentials without walking the full receipt.
 */
@Serializable
data class UpdateReceiptSummary(
    val outcome: String? = null,
    val preSha: String? = null,
    val postSha: String? = null,
    val postVersion: String? = null,
    val fleetStates: List<String>? = null,
)
