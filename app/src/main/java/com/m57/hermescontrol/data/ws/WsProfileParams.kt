package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.local.AuthManager

/**
 * WS-layer analog of [com.m57.hermescontrol.data.remote.ProfileScopeInterceptor].
 *
 * Injects `"profile"` into JSON-RPC `params` for profile-scoped methods so the
 * TUI gateway resolves the correct `HERMES_HOME` / `state.db` for the selected
 * profile.  Without this, WS RPCs always hit the backend's **launch** profile
 * (usually "default"), even after the user switches profiles in the app.
 *
 * Rules (mirror the REST interceptor contract):
 *  - If [AuthManager.getSelectedProfileId] is `null` → pass through unchanged
 *    (legacy single-profile behavior).
 *  - If the caller already supplied an explicit `"profile"` key in [params] →
 *    explicit wins; do **not** overwrite.
 *  - Only decorate methods in [WsMethods.PROFILE_SCOPED_METHODS].  Never touch
 *    global / ops RPCs (`setup.*`, `gateway.*`, `auth.*`, ticket flows, etc.).
 */
object WsProfileParams {
    /**
     * Return a copy of [params] with `"profile"` injected when the active
     * profile is set and the [method] is profile-scoped.  Returns [params]
     * unchanged (same reference) when no injection is needed.
     */
    fun decorate(
        method: String,
        params: Map<String, Any>,
    ): Map<String, Any> {
        // No active profile → legacy single-profile behavior.
        val profile = AuthManager.getSelectedProfileId() ?: return params

        // Only scoped methods get the profile key.
        if (method !in WsMethods.PROFILE_SCOPED_METHODS) return params

        // Explicit profile param from the caller wins.
        if (params.containsKey("profile")) return params

        // Inject profile into a new map (params may be immutable / emptyMap).
        return buildMap(params.size + 1) {
            putAll(params)
            put("profile", profile)
        }
    }
}
