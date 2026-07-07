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
        // Gated mode: the session cookie is now managed automatically by the
        // shared CookieJar (issue #470), so OkHttp re-attaches it on retry
        // without any manual header manipulation here.

        // Loopback mode: re-assert the Bearer token if the request lacked it
        // (or it drifted from the current stored token).
        val token = AuthManager.getToken()
        val requestAuth = response.request.header("Authorization")
        val currentAuthHeader = "Bearer $token"
        if (!token.isNullOrBlank() && (requestAuth == null || !requestAuth.contains(currentAuthHeader))) {
            return response.request
                .newBuilder()
                .header("Authorization", currentAuthHeader)
                .build()
        }
        return null
    }
}
