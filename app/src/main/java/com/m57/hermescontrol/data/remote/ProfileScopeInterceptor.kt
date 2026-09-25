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
 *  - Only backend-confirmed profile-aware endpoints are rewritten. Ops is
 *    intentionally classified route-by-route instead of matching `/api/ops`
 *    wholesale, so future machine-global operations stay untouched.
 */
object ProfileScopeInterceptor : Interceptor {
    private val PROFILE_SCOPED_PREFIXES =
        listOf(
            "/api/analytics",
            // Voice-note transcription resolves STT through the profile's
            // configured provider, so the request must carry the active
            // profile (the desktop sends this route `...profileScoped()`;
            // hermes_cli/web_routers/audio.py is config-scoped). Unscoped, a
            // multi-profile host transcribes under the launch profile
            // (review, PR #1250).
            "/api/audio",
            "/api/config",
            "/api/credentials/pool",
            "/api/cron",
            "/api/env",
            "/api/gateway",
            "/api/mcp",
            "/api/memory",
            "/api/messaging/platforms",
            "/api/messaging/telegram/onboarding",
            "/api/messaging/whatsapp/onboarding",
            "/api/model/info",
            "/api/model/set",
            "/api/model/auxiliary",
            "/api/model/moa",
            "/api/model/options",
            "/api/plugins",
            "/api/sessions",
            "/api/skills",
            "/api/status",
            "/api/tools/toolsets",
            "/api/webhooks",
        )

    /**
     * Mobile-used Ops handlers that accept `?profile=` in Hermes Agent.
     *
     * Keep this explicit rather than adding `/api/ops`: these routes are
     * profile-owned today, while the family itself is not a blanket scoping
     * contract. See `hermes_cli/web_routers/ops.py` and `status.py`.
     */
    private val PROFILE_SCOPED_OPS_PREFIXES =
        listOf(
            "/api/ops/backup",
            "/api/ops/checkpoints",
            "/api/ops/config-migrate",
            "/api/ops/debug-share",
            "/api/ops/doctor",
            "/api/ops/dump",
            "/api/ops/hooks",
            "/api/ops/import-upload",
            "/api/ops/prompt-size",
            "/api/ops/security-audit",
        )

    /**
     * True only when [path] matches a scoped prefix as a whole path segment —
     * e.g. `/api/status` or `/api/status/health`, but NOT `/api/statusXYZ`
     * or `/api/gatewayExtra` (Sourcery review, PR #540).
     */
    private fun matchesPrefix(
        path: String,
        prefix: String,
    ): Boolean = path == prefix || path.startsWith("$prefix/")

    private fun isProfileScopedPath(path: String): Boolean =
        PROFILE_SCOPED_PREFIXES.any { prefix -> matchesPrefix(path, prefix) } ||
            PROFILE_SCOPED_OPS_PREFIXES.any { prefix -> matchesPrefix(path, prefix) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val profile =
            AuthManager.activeProfileId.value
                ?: return chain.proceed(request)

        val url = request.url
        if (url.queryParameter("profile") != null) {
            return chain.proceed(request) // explicit param wins
        }

        // Derive the path relative to the endpoint's proxy prefix so profile
        // scoping works when the dashboard is served behind a reverse proxy
        // (e.g. `/hermes/api/status` → relative `/api/status`). A transient
        // DataStore read failure mid-intercept must not crash every request.
        val relativePath =
            runCatching { AuthManager.endpoint().relativeRequestPath(url) }
                .getOrDefault(url.encodedPath)

        if (!isProfileScopedPath(relativePath)) {
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
