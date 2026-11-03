package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiRead

/**
 * The profile THIS device is scoped to (#1764).
 *
 * The app keeps its active profile locally and scopes requests with `?profile=`,
 * so the server's sticky active profile (shared by every client) is only a
 * fallback for a device that never picked one.
 */
suspend fun fetchActiveProfile(): NetworkResult<ActiveProfileResponse> {
    val local = AuthManager.activeProfileId.value?.takeIf { it.isNotBlank() }
    if (local != null) return NetworkResult.Success(ActiveProfileResponse(active = local))
    return safeApiRead { ApiClient.hermesApi.getActiveProfile() }
}
