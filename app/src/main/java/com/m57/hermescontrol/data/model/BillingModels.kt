package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Request/response models for the billing/subscription WebSocket RPC surface
 * adopted in issue #628 (backend release audit 0bf44d557..614dc194e).
 *
 * Verified against the LIVE gateway (probe on :9119, 2026-07-19): all six
 * methods resolve (no -32601). The real wire shapes differ from the
 * source-described contracts — notably `subscription.state` returns
 * `logged_in` / `current` / `tiers` / `usage` (not the legacy
 * `subscription_type_id` / `renews_at` fields), and the mutating RPCs return
 * an `ok:false` *result* (with `error` / `message` / `payload`) rather than an
 * RPC-level error when unauthenticated. These models track the live shape.
 *
 * All models are decoded with [com.m57.hermescontrol.data.remote.OkHttpProvider.json]
 * (kotlinx `Json { ignoreUnknownKeys = true; SnakeCase }`), so snake_case wire
 * names map to camelCase properties and unknown backend fields are tolerated.
 */

@Serializable
data class SubscriptionStateResponse(
    val ok: Boolean? = null,
    val logged_in: Boolean? = null,
    val is_admin: Boolean? = null,
    val can_change_plan: Boolean? = null,
    val org_name: String? = null,
    val org_id: String? = null,
    val role: String? = null,
    /**
     * "personal" for a direct Nous Portal login, "org" when acting under an
     * organization. Drives which plan/cancel affordances the screen shows.
     */
    val context: String? = null,
    /**
     * The active plan summary when [logged_in] is true. Null when logged out
     * or on the free tier — the screen renders the "no active plan" state then.
     */
    val current: SubscriptionCurrent? = null,
    /**
     * Available plan tiers the user can switch between. Empty when logged out.
     */
    val tiers: List<SubscriptionTier> = emptyList(),
    val portal_url: String? = null,
    val error: String? = null,
    /**
     * Nested usage availability gate returned by the live backend. The actual
     * usage bars come from the separate `usage.bars` RPC.
     */
    val usage: SubscriptionStateUsage? = null,
)

@Serializable
data class SubscriptionCurrent(
    val subscription_type_id: String? = null,
    val subscription_type_name: String? = null,
    val status: String? = null,
    val renews_at: String? = null,
    val cancel_at_period_end: Boolean? = null,
    val payment_method: String? = null,
)

@Serializable
data class SubscriptionTier(
    val subscription_type_id: String? = null,
    val subscription_type_name: String? = null,
    val price: String? = null,
    val currency: String? = null,
    val interval: String? = null,
)

@Serializable
data class SubscriptionStateUsage(
    val available: Boolean? = null,
)

// ── usage.bars ────────────────────────────────────────────────────────────

@Serializable
data class UsageBarsResponse(
    val ok: Boolean? = null,
    val available: Boolean? = null,
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
    val ok: Boolean? = null,
    val error: String? = null,
    val message: String? = null,
    val payload: SubscriptionPreviewPayload? = null,
    val subscription_type_id: String? = null,
    val subscription_type_name: String? = null,
    val price: String? = null,
    val currency: String? = null,
    val interval: String? = null,
    val proration_credit: String? = null,
    val proration_charge: String? = null,
    val summary: String? = null,
)

@Serializable
data class SubscriptionPreviewPayload(
    val actor: String? = null,
    val code: String? = null,
    val recovery: String? = null,
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
    val error: String? = null,
    val status: String? = null,
    val cancel_at_period_end: Boolean? = null,
    val message: String? = null,
)

// ── subscription.resume ───────────────────────────────────────────────────

@Serializable
data class SubscriptionResumeResponse(
    val ok: Boolean? = null,
    val error: String? = null,
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
    val error: String? = null,
    val status: String? = null,
    /** Set when the charge needs SCA / customer action (3-D Secure etc.). */
    val requires_action: Boolean? = null,
    /** Recovery URL the user must open to complete the action. */
    val recovery_url: String? = null,
    /** Set when the charge was declined. */
    val payment_failed: Boolean? = null,
    /** Backend-issued idempotency key (present on the live gateway response). */
    val idempotency_key: String? = null,
    val message: String? = null,
)
