package com.m57.hermescontrol.ui.billing

import com.m57.hermescontrol.data.model.SubscriptionCurrent
import com.m57.hermescontrol.data.model.SubscriptionPreviewResponse
import com.m57.hermescontrol.data.model.SubscriptionResumeResponse
import com.m57.hermescontrol.data.model.SubscriptionStateResponse
import com.m57.hermescontrol.data.model.SubscriptionTier
import com.m57.hermescontrol.data.model.SubscriptionUpgradeResponse
import com.m57.hermescontrol.data.model.UsageBarsResponse
import com.m57.hermescontrol.data.ws.BillingRepository
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: BillingViewModel

    private val sampleSubscription =
        SubscriptionStateResponse(
            ok = true,
            logged_in = true,
            is_admin = true,
            can_change_plan = true,
            org_name = "Acme",
            org_id = "org_123",
            current =
                SubscriptionCurrent(
                    tier_id = "plus",
                    tier_name = "Plus",
                    credits_remaining = "150.00",
                    cycle_ends_at = "2026-09-01",
                ),
            tiers =
                listOf(
                    SubscriptionTier(
                        tier_id = "plus",
                        name = "Plus",
                        tier_order = 1.0,
                        dollars_per_month_display = "$20",
                        is_current = true,
                        is_enabled = true,
                    ),
                    SubscriptionTier(
                        tier_id = "ultra",
                        name = "Ultra",
                        tier_order = 2.0,
                        dollars_per_month_display = "$80",
                        is_current = false,
                        is_enabled = true,
                    ),
                ),
            portal_url = "https://portal.nousresearch.com",
        )

    private val sampleUsage =
        UsageBarsResponse(
            ok = true,
            available = true,
            status = "active",
            plan_name = "Plus",
            subscription_remaining_display = "$150.00",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(BillingRepository)
        coEvery { BillingRepository.getSubscriptionState() } returns sampleSubscription
        coEvery { BillingRepository.getUsageBars() } returns sampleUsage
        viewModel = BillingViewModel()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun load_populatesSubscriptionAndUsage() =
        runTest(testDispatcher) {
            viewModel.load()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(false, state.isLoading)
            assertNotNull(state.subscription)
            assertEquals("Plus", state.subscription?.current?.tier_name)
            assertNotNull(state.usage)
            assertEquals("$150.00", state.usage?.subscription_remaining_display)
            assertNull(state.errorMessage)
        }

    @Test
    fun preview_success_setsPreviewState() =
        runTest(testDispatcher) {
            val previewResp =
                SubscriptionPreviewResponse(
                    ok = true,
                    effect = "charge_now",
                    target_tier_id = "ultra",
                    target_tier_name = "Ultra",
                    amount_due_now_cents = 6000,
                )
            coEvery { BillingRepository.previewSubscription("ultra") } returns previewResp

            viewModel.preview("ultra")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(false, state.isActionInFlight)
            assertEquals(previewResp, state.preview)
            assertEquals("ultra", state.previewingTierId)
        }

    @Test
    fun clearPreview_resetsPreviewAndTierId() =
        runTest(testDispatcher) {
            val previewResp =
                SubscriptionPreviewResponse(
                    ok = true,
                    effect = "charge_now",
                    target_tier_id = "ultra",
                )
            coEvery { BillingRepository.previewSubscription("ultra") } returns previewResp
            viewModel.preview("ultra")
            advanceUntilIdle()

            viewModel.clearPreview()

            val state = viewModel.uiState.value
            assertNull(state.preview)
            assertNull(state.previewingTierId)
        }

    @Test
    fun upgrade_success_setsActionMessageAndReloads() =
        runTest(testDispatcher) {
            val upgradeResp =
                SubscriptionUpgradeResponse(
                    ok = true,
                    status = "upgraded",
                    message = "Upgraded to Ultra",
                )
            coEvery { BillingRepository.upgradeSubscription("ultra") } returns upgradeResp

            viewModel.upgrade("ultra")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(false, state.isActionInFlight)
            assertEquals("Upgrade successful", state.actionMessage)
            assertNull(state.preview)
        }

    @Test
    fun resume_success_setsActionMessageAndReloads() =
        runTest(testDispatcher) {
            val resumeResp =
                SubscriptionResumeResponse(
                    ok = true,
                    message = "Subscription resumed",
                )
            coEvery { BillingRepository.resumeSubscription() } returns resumeResp

            viewModel.resume()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(false, state.isActionInFlight)
            assertEquals("Subscription resumed", state.actionMessage)
        }

    @Test
    fun clearTransientState_clearsAllTransientFields() =
        runTest(testDispatcher) {
            val previewResp =
                SubscriptionPreviewResponse(
                    ok = true,
                    effect = "charge_now",
                )
            coEvery { BillingRepository.previewSubscription("ultra") } returns previewResp
            viewModel.preview("ultra")
            advanceUntilIdle()

            viewModel.clearTransientState()

            val state = viewModel.uiState.value
            assertNull(state.errorMessage)
            assertNull(state.actionMessage)
            assertNull(state.preview)
            assertNull(state.previewingTierId)
        }
}
