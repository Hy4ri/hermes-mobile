package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/**
 * Request/response models for the billing/subscription WebSocket RPC surface
 * adopted in issue #628 (backend release audit 0bf44d557..614dc194e).
 *
 * VERIFIED against the LIVE gateway with a logged-in Nous Portal session
 * (probe on :9119, 2026-07-19). Real shapes captured:
 *  - `subscription.state` -> ok/logged_in/is_admin/can_change_plan/org_name/
 *    role/context + `current` {tier_id, tier_name, monthly_credits,
 *    credits_remaining, cycle_ends_at, pending_downgrade_*} + `tiers`.
 *  - `usage.bars` -> ok/available/status/plan_name/renews_at +
 *    `plan_bar` {kind, remaining_display, total_display, spent_display,
 *    pct_used, fill_fraction} + `topup_bar` (nullable).
 *  - Mutating RPCs (preview/change/resume/upgrade) -> `ok:false` *result*
 *    with `error`/`message`/`payload`; when the Portal token lacks the
 *    `billing:manage` scope they return error:"insufficient_scope".
 *
 * All models decode with [com.m57.hermescontrol.data.remote.OkHttpProvider.json]
 * (kotlinx `Json { ignoreUnknownKeys = true; SnakeCase }`). The `SnakeCase`
 * naming strategy maps snake_case wire keys directly onto snake_case Kotlin
 * properties, so the property names below mirror the backend JSON exactly and
 * unknown backend fields are tolerated.
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
    /** "personal" for a direct Nous Portal login, "org" when under an org. */
    val context: String? = null,
    /** Active plan summary when [logged_in] is true; null when logged out. */
    val current: SubscriptionCurrent? = null,
    /** Available plan tiers the user can switch between (empty when logged out). */
    val tiers: List<SubscriptionTier> = emptyList(),
    val portal_url: String? = null,
    val error: String? = null,
)

@Serializable
data class SubscriptionCurrent(
    val tier_id: String? = null,
    val tier_name: String? = null,
    /** Monthly credit grant, as a string decimal (e.g. "0.1"). */
    val monthly_credits: String? = null,
    /** Credits remaining this cycle, as a string decimal. */
    val credits_remaining: String? = null,
    /** ISO timestamp when the current cycle ends. */
    val cycle_ends_at: String? = null,
    val pending_downgrade_tier_name: String? = null,
    val pending_downgrade_at: String? = null,
    val pending_downgrade_display: String? = null,
)

@Serializable
data class SubscriptionTier(
    val tier_id: String? = null,
    val tier_name: String? = null,
    val price: String? = null,
    val currency: String? = null,
    val interval: String? = null,
)

// ── usage.bars ────────────────────────────────────────────────────────────

@Serializable
data class UsageBarsResponse(
    val ok: Boolean? = null,
    val available: Boolean? = null,
    /** e.g. "depleted" / "active" — high-level usage status. */
    val status: String? = null,
    val plan_name: String? = null,
    val renews_at: String? = null,
    val renews_display: String? = null,
    val subscription_remaining_display: String? = null,
    val topup_remaining_display: String? = null,
    val total_spendable_display: String? = null,
    val has_topup: Boolean? = null,
    val plan_bar: UsageBar? = null,
    val topup_bar: UsageBar? = null,
)

@Serializable
data class UsageBar(
    val kind: String? = null,
    val remaining_display: String? = null,
    val total_display: String? = null,
    val spent_display: String? = null,
    /** 0..100 percentage used (not a fraction). */
    val pct_used: Double? = null,
    /** 0.0..1.0 fill fraction for a progress indicator. */
    val fill_fraction: Double? = null,
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
    val error: String? = null,
    val error_description: String? = null,
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
