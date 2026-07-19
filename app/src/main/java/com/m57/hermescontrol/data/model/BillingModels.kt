package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Request/response models for the billing/subscription WebSocket RPC surface
 * adopted in issue #628 (backend release audit 0bf44d557..614dc194e).
 *
 * All models are decoded with [com.m57.hermescontrol.data.remote.OkHttpProvider.json]
 * (kotlinx `Json { ignoreUnknownKeys = true; SnakeCase }`), so snake_case wire
 * names map to camelCase properties and unknown backend fields are tolerated.
 */

@Serializable
data class SubscriptionStateResponse(
    val logged_in: Boolean = false,
    val subscription_type_id: String? = null,
    val subscription_type_name: String? = null,
    val status: String? = null,
    val renews_at: String? = null,
    val cancel_at_period_end: Boolean? = null,
    val payment_method: String? = null,
    val portal_url: String? = null,
)

// ── usage.bars ────────────────────────────────────────────────────────────

@Serializable
data class UsageBarsResponse(
    val period_start: String? = null,
    val period_end: String? = null,
    val bars: List<UsageBar> = emptyList(),
)

@Serializable
data class UsageBar(
    val label: String? = null,
    val used: Double? = null,
    val limit: Double? = null,
    val unit: String? = null,
)

// ── subscription.preview ──────────────────────────────────────────────────

@Serializable
data class SubscriptionPreviewRequest(
    val subscription_type_id: String,
)

@Serializable
data class SubscriptionPreviewResponse(
    val subscription_type_id: String? = null,
    val subscription_type_name: String? = null,
    val price: String? = null,
    val currency: String? = null,
    val interval: String? = null,
    val proration_credit: String? = null,
    val proration_charge: String? = null,
    val summary: String? = null,
)

// ── subscription.change ───────────────────────────────────────────────────

@Serializable
data class SubscriptionChangeRequest(
    val subscription_type_id: String? = null,
    val cancel: Boolean? = null,
)

@Serializable
data class SubscriptionChangeResponse(
    val ok: Boolean? = null,
    val status: String? = null,
    val cancel_at_period_end: Boolean? = null,
    val message: String? = null,
)

// ── subscription.resume ───────────────────────────────────────────────────

@Serializable
data class SubscriptionResumeResponse(
    val ok: Boolean? = null,
    val status: String? = null,
    val message: String? = null,
)

// ── subscription.upgrade ──────────────────────────────────────────────────

@Serializable
data class SubscriptionUpgradeRequest(
    val subscription_type_id: String,
)

@Serializable
data class SubscriptionUpgradeResponse(
    val ok: Boolean? = null,
    val status: String? = null,
    /** Set when the charge needs SCA / customer action (3-D Secure etc.). */
    val requires_action: Boolean? = null,
    /** Recovery URL the user must open to complete the action. */
    val recovery_url: String? = null,
    /** Set when the charge was declined. */
    val payment_failed: Boolean? = null,
    val message: String? = null,
)
