package com.m57.hermescontrol.data.local

/**
 * The app's single source of truth for "where am I + who am I".
 *
 * Emitted by [AuthManager.contextFlow] whenever the connected server
 * ([baseUrl]), its [token], or the selected [profileId] changes — so
 * screens and the switch coordinator can re-home reactively instead of
 * each layer tracking its own slice of connection state.
 */
data class ProfileContext(
    val baseUrl: String,
    val token: String?,
    val profileId: String?,
) {
    /** True when a non-default profile is explicitly selected. */
    val isProfileScoped: Boolean
        get() = profileId != null && profileId != AuthManager.DEFAULT_PROFILE_ID
}
