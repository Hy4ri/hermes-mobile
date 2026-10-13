package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Blind regression test for SubscriptionUpgradeResponse real producer-shaped decoding (B1551).
 *
 * Requirements from contracts.md (B1551):
 * - Authoritative producer types apps/shared/src/billing-types.ts + hermes_cli/cli_billing_mixin.py.
 * - Append optional reason: String? and target_tier_name: String? to SubscriptionUpgradeResponse,
 *   preserving positional existing fields.
 * - Real Json producer fixtures: authoritative statuses upgraded, already_on_tier, requires_action, payment_failed.
 * - Existing fields ok, error, status, requires_action, recovery_url, payment_failed, idempotency_key, message optional;
 *   append reason, target_tier_name.
 * - Tests producer-shaped decoding through real serializer.
 */
class BillingUpgradeResponseTest {
    private val json: Json = OkHttpProvider.json

    @Test
    fun decodesUpgradedSuccessPayload() {
        val raw =
            """
            {
                "ok": true,
                "status": "upgraded",
                "target_tier_name": "Pro Tier",
                "idempotency_key": "idem-12345"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(true, decoded.ok)
        assertEquals("upgraded", decoded.status)
        assertEquals("Pro Tier", decoded.target_tier_name)
        assertEquals("idem-12345", decoded.idempotency_key)
        assertNull(decoded.reason)
        assertNull(decoded.recovery_url)
        assertNull(decoded.error)
        assertNull(decoded.message)
    }

    @Test
    fun decodesAlreadyOnTierPayload() {
        val raw =
            """
            {
                "ok": true,
                "status": "already_on_tier",
                "target_tier_name": "Ultra Tier",
                "message": "Already subscribed to Ultra Tier"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(true, decoded.ok)
        assertEquals("already_on_tier", decoded.status)
        assertEquals("Ultra Tier", decoded.target_tier_name)
        assertEquals("Already subscribed to Ultra Tier", decoded.message)
    }

    @Test
    fun decodesRequiresActionWithRecoveryUrl() {
        val raw =
            """
            {
                "ok": false,
                "status": "requires_action",
                "recovery_url": "https://portal.nousresearch.com/billing/3ds",
                "requires_action": true,
                "message": "Action required by your bank"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(false, decoded.ok)
        assertEquals("requires_action", decoded.status)
        assertEquals("https://portal.nousresearch.com/billing/3ds", decoded.recovery_url)
        assertEquals(true, decoded.requires_action)
        assertEquals("Action required by your bank", decoded.message)
    }

    @Test
    fun decodesPaymentFailedWithReasonAndRecoveryUrl() {
        val raw =
            """
            {
                "ok": false,
                "status": "payment_failed",
                "payment_failed": true,
                "reason": "card_declined",
                "recovery_url": "https://portal.nousresearch.com/billing/cards",
                "error": "card_declined",
                "message": "Your card was declined."
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(false, decoded.ok)
        assertEquals("payment_failed", decoded.status)
        assertEquals(true, decoded.payment_failed)
        assertEquals("card_declined", decoded.reason)
        assertEquals("https://portal.nousresearch.com/billing/cards", decoded.recovery_url)
        assertEquals("card_declined", decoded.error)
        assertEquals("Your card was declined.", decoded.message)
    }

    @Test
    fun decodesAuthenticationRequiredScaReason() {
        val raw =
            """
            {
                "ok": false,
                "status": "payment_failed",
                "reason": "authentication_required",
                "recovery_url": "https://portal.nousresearch.com/billing/verify-card"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(false, decoded.ok)
        assertEquals("payment_failed", decoded.status)
        assertEquals("authentication_required", decoded.reason)
        assertEquals("https://portal.nousresearch.com/billing/verify-card", decoded.recovery_url)
    }

    @Test
    fun decodesSubscriptionPaymentIntentRequiresActionScaReason() {
        val raw =
            """
            {
                "ok": false,
                "status": "payment_failed",
                "reason": "subscription_payment_intent_requires_action",
                "recovery_url": "https://portal.nousresearch.com/billing/sca"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(false, decoded.ok)
        assertEquals("payment_failed", decoded.status)
        assertEquals("subscription_payment_intent_requires_action", decoded.reason)
        assertEquals("https://portal.nousresearch.com/billing/sca", decoded.recovery_url)
    }

    @Test
    fun decodesLegacyAliasesWhenStatusMissing() {
        val raw =
            """
            {
                "ok": false,
                "requires_action": true,
                "recovery_url": "https://portal.nousresearch.com/billing/legacy-3ds",
                "idempotency_key": "idem-legacy"
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(false, decoded.ok)
        assertNull(decoded.status)
        assertEquals(true, decoded.requires_action)
        assertEquals("https://portal.nousresearch.com/billing/legacy-3ds", decoded.recovery_url)
        assertEquals("idem-legacy", decoded.idempotency_key)
    }

    @Test
    fun decodesMinimalPayloadWithDefaults() {
        val raw =
            """
            {
                "ok": true
            }
            """.trimIndent()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(true, decoded.ok)
        assertNull(decoded.status)
        assertNull(decoded.reason)
        assertNull(decoded.target_tier_name)
        assertNull(decoded.recovery_url)
        assertNull(decoded.error)
        assertNull(decoded.message)
        assertNull(decoded.requires_action)
        assertNull(decoded.payment_failed)
        assertNull(decoded.idempotency_key)
    }

    @Test
    fun serializesAndRoundTripsFieldsFaithfully() {
        val original =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "upgraded",
                target_tier_name = "Max Tier",
                reason = null,
                idempotency_key = "key-abc",
            )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(encoded)

        assertEquals(original.ok, decoded.ok)
        assertEquals(original.status, decoded.status)
        assertEquals(original.target_tier_name, decoded.target_tier_name)
        assertEquals(original.idempotency_key, decoded.idempotency_key)
    }

    @Test
    fun decodesExtraProducerFieldsWithoutFailure() {
        val raw =
            buildJsonObject {
                put("ok", true)
                put("status", "upgraded")
                put("target_tier_name", "Enterprise")
                put("actor", "user_123")
                put("code", "UPGRADE_DONE")
                put("portal_url", "https://portal.nousresearch.com")
                put("retry_after", 30)
            }.toString()

        val decoded = json.decodeFromString<SubscriptionUpgradeResponse>(raw)

        assertEquals(true, decoded.ok)
        assertEquals("upgraded", decoded.status)
        assertEquals("Enterprise", decoded.target_tier_name)
    }
}
