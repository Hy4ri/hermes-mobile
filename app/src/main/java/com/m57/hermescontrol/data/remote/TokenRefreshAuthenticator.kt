package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
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

        // Loopback mode: a dashboard restart invalidates the saved ephemeral
        // token. First reuse a token another request may already have refreshed;
        // otherwise extract the current token from the dashboard SPA.
        val token = AuthManager.getToken()
        val requestAuth = response.request.header("Authorization")
        val currentAuthHeader = "Bearer $token"
        if (!token.isNullOrBlank() && (requestAuth == null || !requestAuth.contains(currentAuthHeader))) {
            return response.request
                .newBuilder()
                .header("Authorization", currentAuthHeader)
                .build()
        }

        val refreshedToken = DashboardSessionTokenRefresher.refresh() ?: return null
        return response.request
            .newBuilder()
            .header("Authorization", "Bearer $refreshedToken")
            .build()
    }
}
