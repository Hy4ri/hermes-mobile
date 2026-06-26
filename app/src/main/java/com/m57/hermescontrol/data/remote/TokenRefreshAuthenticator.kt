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
        val sessionCookie = AuthManager.getSessionCookie()
        if (!sessionCookie.isNullOrBlank()) {
            val requestCookie = response.request.header("Cookie")
            val currentCookieHeader = "hermes_session_at=$sessionCookie"
            if (requestCookie == null || !requestCookie.contains(currentCookieHeader)) {
                return response.request
                    .newBuilder()
                    .header("Cookie", currentCookieHeader)
                    .build()
            }
        } else {
            val token = AuthManager.getToken()
            val requestAuth = response.request.header("Authorization")
            val currentAuthHeader = "Bearer $token"
            if (!token.isNullOrBlank() && (requestAuth == null || !requestAuth.contains(currentAuthHeader))) {
                return response.request
                    .newBuilder()
                    .header("Authorization", currentAuthHeader)
                    .build()
            }
        }
        return null
    }
}
