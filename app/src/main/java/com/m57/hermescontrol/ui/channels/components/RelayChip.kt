package com.m57.hermescontrol.ui.channels.components

import com.m57.hermescontrol.data.model.MessagingPlatform

/**
 * Which Team Gateway chip a channel card carries (issue #461).
 *
 * The relay-exclusive rule ("GATEWAY_RELAY_URL" without "GATEWAY_RELAY_ALLOW_DIRECT_PLATFORMS")
 * switches a platform's direct adapter off at every gateway start, so its "disabled" state is the
 * relay's decision, not a user setting. The card names the relay instead of offering a dead toggle,
 * mirroring the dashboard (`web/src/pages/ChannelsPage.tsx` -> `platformBadge`) and Desktop.
 */
internal enum class RelayChip {
    /** The relay owns the platform outright: the direct adapter never runs. */
    VIA_RELAY,

    /** The relay turns this platform's direct connection off at every start; the user did not. */
    MANAGED_BY_RELAY,

    /** The direct connection still applies, and the shared bot carries the platform too. */
    SHARED_BOT_ONLY,

    /** No Team Gateway involvement on this row. */
    NONE,
}

/**
 * Relay flags win over the platform's own state, in the order the backend sets them:
 * exclusivity implies "disabled by relay", which implies the platform is relay fronted.
 */
internal fun relayChipFor(platform: MessagingPlatform): RelayChip =
    when {
        platform.relayExclusive -> RelayChip.VIA_RELAY
        platform.disabledByRelay -> RelayChip.MANAGED_BY_RELAY
        platform.relayFronted -> RelayChip.SHARED_BOT_ONLY
        else -> RelayChip.NONE
    }
