package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.data.local.AuthManager
import okhttp3.OkHttpClient
import okhttp3.Request

internal object DashboardSessionTokenRefresher {
    private val tokenPattern = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")

    fun refresh(): String? =
        try {
            val token = fetch(AuthManager.baseUrl(), OkHttpProvider.probe) ?: return null
            AuthManager.setToken(token)
            token
        } catch (_: Exception) {
            null
        }

    internal fun fetch(
        baseUrl: String,
        client: OkHttpClient,
    ): String? =
        try {
            val request = Request.Builder().url(baseUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                tokenPattern
                    .find(response.body.string())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
}
