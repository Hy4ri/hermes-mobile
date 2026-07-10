package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Appends `?profile=<activeProfile>` to profile-scoped management endpoints,
 * mirroring the desktop/web dashboard contract
 * (`web/src/lib/api.ts` → `PROFILE_SCOPED_PREFIXES` + `withManagementProfile`).
 *
 * Without this, mobile fired all management REST calls with NO profile scope,
 * so every profile read/wrote the backend's implicit default profile —
 * multi-profile setups silently corrupted config/skills/toolsets/mcp/model/env
 * (issue #528).
 *
 * Rules (copied from the desktop contract):
 *  - If no profile is active, pass through unchanged (legacy "default" behavior).
 *  - If the URL already carries an explicit `profile=` query, leave it
 *    untouched — explicit beats global.
 *  - Only the endpoint families below honor `?profile=` on the backend;
 *    everything else (ops, pairing, cron, profiles themselves) is
 *    machine-global or self-scoped and must NOT be rewritten.
 */
object ProfileScopeInterceptor : Interceptor {
    private val PROFILE_SCOPED_PREFIXES =
        listOf(
            "/api/status",
            "/api/gateway",
            "/api/analytics",
            "/api/skills",
            "/api/tools/toolsets",
            "/api/config",
            "/api/env",
            "/api/mcp",
            "/api/messaging/platforms",
            "/api/messaging/telegram/onboarding",
            "/api/messaging/whatsapp/onboarding",
            "/api/model/info",
            "/api/model/set",
            "/api/model/auxiliary",
            "/api/model/moa",
            "/api/model/options",
        )

    /**
     * True only when [path] matches a scoped prefix as a whole path segment —
     * e.g. `/api/status` or `/api/status/health`, but NOT `/api/statusXYZ`
     * or `/api/gatewayExtra` (Sourcery review, PR #540).
     */
    private fun isProfileScopedPath(path: String): Boolean =
        PROFILE_SCOPED_PREFIXES.any { prefix -> path == prefix || path.startsWith("$prefix/") }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val profile =
            AuthManager.getSelectedProfileId()
                ?: return chain.proceed(request)

        val url = request.url
        if (url.queryParameter("profile") != null) {
            return chain.proceed(request) // explicit param wins
        }

        if (!isProfileScopedPath(url.encodedPath)) {
            return chain.proceed(request)
        }

        val scopedUrl =
            url
                .newBuilder()
                .addQueryParameter("profile", profile)
                .build()

        return chain.proceed(request.newBuilder().url(scopedUrl).build())
    }
}
