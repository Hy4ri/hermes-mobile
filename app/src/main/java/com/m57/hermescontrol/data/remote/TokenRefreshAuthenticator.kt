package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

object TokenRefreshAuthenticator : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        if (response.priorResponse != null) {
            return null // Only retry once
        }
        // Gated mode (session-cookie auth): a REST 401 here means the session
        // cookie is genuinely expired (the shared CookieJar re-attaches it on
        // retry, issue #470). Stamping a Bearer header would 401 again, and the
        // SPA token refresher only works in loopback mode — so bail out and let
        // safeApiCall() route to AuthSessionState.requireSignIn().
        if (AuthManager.isGatedMode()) return null

        val requestAuth = response.request.header("Authorization")
        val failedToken = extractBearerToken(requestAuth) ?: return null
        val expectedScope = response.request.tag(DataScope::class.java) ?: AuthManager.currentDataScope()
        if (expectedScope != null && AuthManager.currentDataScope() != expectedScope) return null

        // #1552: token freshness is checked under the shared recovery gate after ownership checks.
        val refreshedToken =
            DashboardSessionTokenRefresher.refreshIfStale(
                failedToken = failedToken,
                expectedScope = expectedScope,
            ) ?: return null

        if (AuthManager.currentDataScope() != expectedScope) return null

        return response.request
            .newBuilder()
            .header("Authorization", "Bearer $refreshedToken")
            .build()
    }

    private fun extractBearerToken(authHeader: String?): String? {
        if (authHeader == null) return null
        val trimmed = authHeader.trim()
        if (trimmed.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
            return trimmed.substring(7).trim().takeIf { it.isNotBlank() }
        }
        return null
    }
}
