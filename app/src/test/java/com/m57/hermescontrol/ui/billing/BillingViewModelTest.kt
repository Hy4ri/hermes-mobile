package com.m57.hermescontrol.ui.billing

import com.m57.hermescontrol.data.model.SubscriptionCurrent
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.data.model.SubscriptionUpgradeResponse
import com.m57.hermescontrol.data.ws.BillingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Blind tests for B1551: verified upgrade outcomes on BillingViewModel.
 *
 * Requirements from contracts.md (B1551):
 * - Public BillingViewModel.upgrade(subscriptionTypeId: String).
 * - uiState StateFlow with errorMessage / actionMessage / isActionInFlight.
 * - BillingRepository.upgradeSubscription returns SubscriptionUpgradeResponse?.
 * - Load follows accepted statuses and boundary BillingRepository can read.
 * - Authoritative statuses: upgraded and already_on_tier (NOT active).
 * - ok=true alone, missing or unknown status never proves success; report unconfirmed/requested
 *   without promising charge or upgrade, no automatic repeat charge.
 * - status precedence over old requires_action/payment_failed aliases; aliases fallback when status missing.
 * - requires_action exposes recovery_url with explanation.
 * - payment_failed surfaces reason/recovery, not success.
 * - Known reason authentication_required / subscription_payment_intent_requires_action means
 *   verify-card recovery even if legacy status is payment_failed.
 * - ok=false failure overrides success.
 * - upgraded requires ok=true.
 * - already_on_tier gives honest already-on-plan message.
 * - In-flight guard: concurrent upgrade requests or busy guard prevents duplicate in-flight upgrade calls,
 *   and isActionInFlight is cleaned up upon completion or failure.
 * - Mock EVERY real billing boundary: no actual network or payment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(BillingRepository)
        coEvery { BillingRepository.getSubscriptionState() } returns
            SubscriptionStateResponse(
                current = SubscriptionCurrent(tier_id = "free", tier_name = "Free"),
            )
        coEvery { BillingRepository.getUsageBars() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): BillingViewModel {
        val vm = BillingViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun upgradeSuccess_statusUpgraded_updatesStateAndTriggersReload() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "upgraded",
                target_tier_name = "Pro Plan",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        assertNull(state.errorMessage)
        assertNotNull(state.actionMessage)
        val msg = state.actionMessage.orEmpty()
        assertTrue(
            "Expected success message for upgraded status, got: $msg",
            msg.contains("upgraded", ignoreCase = true) ||
                msg.contains("Pro Plan", ignoreCase = true) ||
                msg.contains("success", ignoreCase = true),
        )
        // Upgraded status triggers load to refresh subscription state
        coVerify(atLeast = 1) { BillingRepository.getSubscriptionState() }
    }

    @Test
    fun upgradeSuccess_statusAlreadyOnTier_honestMessageAndTriggersReload() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "already_on_tier",
                target_tier_name = "Pro Plan",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        assertNull(state.errorMessage)
        assertNotNull(state.actionMessage)
        val msg = state.actionMessage.orEmpty()
        assertTrue(
            "Expected honest already-on-tier message, got: $msg",
            msg.contains("already", ignoreCase = true) || msg.contains("Pro Plan", ignoreCase = true),
        )
        coVerify(atLeast = 1) { BillingRepository.getSubscriptionState() }
    }

    @Test
    fun upgradeFailed_okFalse_overridesSuccessStatus() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = "upgraded",
                error = "card_error",
                message = "Payment failed to authorize",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        // ok=false must never report successful upgrade
        val action = state.actionMessage.orEmpty()
        assertFalse(
            "ok=false must not report upgrade success, got actionMessage: $action",
            action.contains("upgraded successfully", ignoreCase = true) ||
                action.contains("Upgrade successful", ignoreCase = true),
        )
        val error = state.errorMessage.orEmpty()
        assertTrue(
            "Expected error or failure message surfaced, error: '$error', action: '$action'",
            error.contains("Payment failed", ignoreCase = true) ||
                error.contains("card_error", ignoreCase = true) ||
                action.contains("Payment failed", ignoreCase = true),
        )
    }

    @Test
    fun upgradeRequiresAction_surfacesRecoveryUrlAndExplanation() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = "requires_action",
                recovery_url = "https://portal.nousresearch.com/billing/3ds",
                message = "3D Secure verification required",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val combined = "${state.errorMessage.orEmpty()} ${state.actionMessage.orEmpty()}"
        assertTrue(
            "Expected recovery url or verification notice in state, got: $combined",
            combined.contains("3ds", ignoreCase = true) ||
                combined.contains("verification", ignoreCase = true) ||
                combined.contains("portal", ignoreCase = true) ||
                combined.contains("action", ignoreCase = true),
        )
        assertFalse(
            "Must not claim upgrade success when requires_action",
            combined.contains("Upgrade successful", ignoreCase = true),
        )
    }

    @Test
    fun upgradePaymentFailed_withCardDeclinedReason_surfacesDeclinedAndRecovery() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = "payment_failed",
                reason = "card_declined",
                recovery_url = "https://portal.nousresearch.com/billing/payment-methods",
                message = "Card declined by issuer",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val combined = "${state.errorMessage.orEmpty()} ${state.actionMessage.orEmpty()}"
        assertTrue(
            "Expected card decline or recovery to be surfaced, got: $combined",
            combined.contains("decline", ignoreCase = true) ||
                combined.contains("card", ignoreCase = true) ||
                combined.contains("failed", ignoreCase = true),
        )
        assertFalse(
            "Must not claim upgrade success on payment_failed",
            combined.contains("Upgrade successful", ignoreCase = true),
        )
    }

    @Test
    fun upgradePaymentFailed_withScaReason_takesPrecedenceAsVerifyCardRecovery() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = "payment_failed",
                reason = "authentication_required",
                recovery_url = "https://portal.nousresearch.com/billing/verify-card",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val combined = "${state.errorMessage.orEmpty()} ${state.actionMessage.orEmpty()}"
        assertTrue(
            "Expected verification / card check indication due to authentication_required reason, got: $combined",
            combined.contains("verify", ignoreCase = true) ||
                combined.contains("card", ignoreCase = true) ||
                combined.contains("authentication", ignoreCase = true) ||
                combined.contains("portal", ignoreCase = true),
        )
        assertFalse(
            "Must not report upgrade success",
            combined.contains("Upgrade successful", ignoreCase = true),
        )
    }

    @Test
    fun upgradePaymentFailed_withSubscriptionPaymentIntentRequiresActionReason_mapsToScaCopy() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = "payment_failed",
                reason = "subscription_payment_intent_requires_action",
                recovery_url = "https://portal.nousresearch.com/billing/sca-portal",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val combined = "${state.errorMessage.orEmpty()} ${state.actionMessage.orEmpty()}"
        assertTrue(
            "Expected SCA verification copy due to subscription_payment_intent_requires_action, got: $combined",
            combined.contains("verify", ignoreCase = true) ||
                combined.contains("card", ignoreCase = true) ||
                combined.contains("action", ignoreCase = true) ||
                combined.contains("portal", ignoreCase = true),
        )
    }

    @Test
    fun upgradeMissingStatus_fallsBackToLegacyAliases() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = null,
                requires_action = true,
                recovery_url = "https://portal.nousresearch.com/billing/legacy-3ds",
                message = "Legacy action needed",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val combined = "${state.errorMessage.orEmpty()} ${state.actionMessage.orEmpty()}"
        assertTrue(
            "Expected requires_action fallback when status is null, got: $combined",
            combined.contains("action", ignoreCase = true) ||
                combined.contains("portal", ignoreCase = true) ||
                combined.contains("legacy", ignoreCase = true) ||
                combined.contains("verify", ignoreCase = true),
        )
        assertFalse(
            "Must not report success for legacy requires_action alias",
            combined.contains("Upgrade successful", ignoreCase = true),
        )
    }

    @Test
    fun upgradeMissingStatus_paymentFailedAliasFallback() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = null,
                payment_failed = true,
                error = "insufficient_funds",
                message = "Insufficient funds",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val combined = "${state.errorMessage.orEmpty()} ${state.actionMessage.orEmpty()}"
        assertTrue(
            "Expected payment failure surfaced via alias fallback, got: $combined",
            combined.contains("failed", ignoreCase = true) ||
                combined.contains("insufficient", ignoreCase = true) ||
                combined.contains("funds", ignoreCase = true),
        )
    }

    @Test
    fun upgradeUnknownStatus_withOkTrue_neverClaimsSuccess() {
        // Invented or unconfirmed status 'active' or unknown status
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "active",
                message = "Plan is active",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val action = state.actionMessage.orEmpty()
        assertFalse(
            "Status 'active' is not an authoritative success status; must not claim Upgrade successful, got: $action",
            action.equals("Upgrade successful", ignoreCase = true) ||
                action.equals("Upgraded successfully", ignoreCase = true),
        )
        val combined = "${state.errorMessage.orEmpty()} $action"
        assertTrue(
            "Must report unconfirmed, requested, or raw message without promising upgrade, got: $combined",
            combined.contains("unconfirmed", ignoreCase = true) ||
                combined.contains("requested", ignoreCase = true) ||
                combined.contains("active", ignoreCase = true),
        )
    }

    @Test
    fun upgradeMissingStatus_withOkTrue_neverClaimsSuccess() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = null,
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val action = state.actionMessage.orEmpty()
        assertFalse(
            "Missing status cannot claim Upgrade successful, got: $action",
            action.equals("Upgrade successful", ignoreCase = true) ||
                action.equals("Upgraded successfully", ignoreCase = true),
        )
    }

    @Test
    fun upgradeNullResponse_surfacesErrorMessageAndReleasesInFlight() {
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns null

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        assertNotNull(state.errorMessage)
        val error = state.errorMessage.orEmpty()
        assertTrue(
            "Expected error message indicating unconfirmed/no billing data, got: $error",
            error.isNotBlank(),
        )
    }

    @Test
    fun upgradeDeterministicQueuedDoubleTap_beforeDispatcherRuns_triggersExactlyOneCall() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "upgraded",
                target_tier_name = "Pro Plan",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()

        // Two taps queued deterministically BEFORE testDispatcher runs
        viewModel.upgrade("tier-pro")
        viewModel.upgrade("tier-pro")

        // Now run queued coroutines
        testDispatcher.scheduler.runCurrent()

        // Must invoke BillingRepository.upgradeSubscription exactly once
        coVerify(exactly = 1) { BillingRepository.upgradeSubscription("tier-pro") }
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isActionInFlight)
    }

    @Test
    fun upgradeFailureCleansGuard_allowingLaterRetryAttempt() {
        val failureResp =
            SubscriptionUpgradeResponse(
                ok = false,
                status = "payment_failed",
                error = "card_error",
                message = "Payment failed to authorize",
            )
        val successResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "upgraded",
                target_tier_name = "Pro Plan",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returnsMany listOf(failureResp, successResp)

        val viewModel = createViewModel()

        // First attempt fails
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isActionInFlight)
        coVerify(exactly = 1) { BillingRepository.upgradeSubscription("tier-pro") }

        // Later retry intent is permissible and succeeds
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isActionInFlight)
        coVerify(exactly = 2) { BillingRepository.upgradeSubscription("tier-pro") }
        val action =
            viewModel.uiState.value.actionMessage
                .orEmpty()
        assertTrue(
            "Expected success message on retry, got: $action",
            action.contains("upgraded", ignoreCase = true) || action.contains("Pro Plan", ignoreCase = true),
        )
    }

    @Test
    fun upgradeNullStatus_withMaliciousLegacyMessage_showsUnconfirmedAndNoUpgradedSuccess() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = null,
                message = "Upgrade successful",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val action = state.actionMessage.orEmpty()
        val error = state.errorMessage.orEmpty()
        val combined = "$error $action"

        // MUST NOT claim upgraded or success
        assertFalse(
            "Null status with malicious legacy message must not claim upgraded success, got action: $action",
            action.equals("Upgrade successful", ignoreCase = true) ||
                action.equals("Upgraded successfully", ignoreCase = true) ||
                action.contains("upgraded successfully", ignoreCase = true),
        )
        // MUST show unconfirmed / requested
        assertTrue(
            "Null status must show unconfirmed/requested state, got combined: $combined",
            combined.contains("unconfirmed", ignoreCase = true) ||
                combined.contains("requested", ignoreCase = true),
        )
    }

    @Test
    fun upgradeUnknownStatus_withMaliciousLegacyMessage_showsUnconfirmedAndNoUpgradedSuccess() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "unknown_status_foo",
                message = "Upgrade successful",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val action = state.actionMessage.orEmpty()
        val error = state.errorMessage.orEmpty()
        val combined = "$error $action"

        // MUST NOT claim upgraded or success
        assertFalse(
            "Unknown status with malicious legacy message must not claim upgraded success, got action: $action",
            action.equals("Upgrade successful", ignoreCase = true) ||
                action.equals("Upgraded successfully", ignoreCase = true) ||
                action.contains("upgraded successfully", ignoreCase = true),
        )
        // MUST show unconfirmed / requested
        assertTrue(
            "Unknown status must show unconfirmed/requested state, got combined: $combined",
            combined.contains("unconfirmed", ignoreCase = true) ||
                combined.contains("requested", ignoreCase = true),
        )
    }

    @Test
    fun upgradeAuthoritativeFailed_withOkTrue_retainsRecoveryAndNeverClaimsSuccess() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "payment_failed",
                reason = "authentication_required",
                recovery_url = "https://portal.nousresearch.com/billing/verify-card",
                message = "Upgrade successful",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isActionInFlight)
        val action = state.actionMessage.orEmpty()
        val error = state.errorMessage.orEmpty()
        val combined = "$error $action"

        // Authoritative failed status overrides ok=true and message
        assertFalse(
            "Authoritative failed status must not claim success despite ok=true, got action: $action",
            action.equals("Upgrade successful", ignoreCase = true) ||
                action.equals("Upgraded successfully", ignoreCase = true),
        )
        // Retains recovery information (URL or verification prompt)
        assertTrue(
            "Authoritative failed status with recovery must retain recovery, got combined: $combined",
            combined.contains("verify", ignoreCase = true) ||
                combined.contains("portal", ignoreCase = true) ||
                combined.contains("authentication", ignoreCase = true) ||
                combined.contains("failed", ignoreCase = true),
        )
    }

    @Test
    fun upgradeInFlightGuard_ignoresConcurrentCallsWhileBusy() {
        val upgradeResp =
            SubscriptionUpgradeResponse(
                ok = true,
                status = "upgraded",
                target_tier_name = "Pro Plan",
            )
        coEvery { BillingRepository.upgradeSubscription("tier-pro") } returns upgradeResp

        val viewModel = createViewModel()

        // Call upgrade twice in immediate succession
        viewModel.upgrade("tier-pro")
        viewModel.upgrade("tier-pro")
        testDispatcher.scheduler.advanceUntilIdle()

        // Should have only triggered upgrade once due to in-flight guard
        coVerify(exactly = 1) { BillingRepository.upgradeSubscription("tier-pro") }
        assertFalse(viewModel.uiState.value.isActionInFlight)
    }
}
