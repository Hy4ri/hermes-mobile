package com.m57.hermescontrol.ui.chat.markdown

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.GatewayFileClient

object MarkdownMediaResolver {
    fun resolveImageUrl(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return uri
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:image/")) {
            return trimmed
        }
        val baseUrl = runCatching { AuthManager.getBaseUrl() }.getOrDefault("")
        val token = runCatching { AuthManager.getToken() }.getOrNull().orEmpty()
        val mediaUrl = GatewayFileClient.buildMediaUrl(baseUrl, token, trimmed)
        if (mediaUrl != null) return mediaUrl

        if (baseUrl.isNotBlank() && (trimmed.startsWith("/api/") || trimmed.startsWith("api/"))) {
            val cleanPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            val sep = if (cleanPath.contains("?")) "&" else "?"
            return if (token.isNotBlank() && !cleanPath.contains("token=")) {
                "${baseUrl.trimEnd('/')}$cleanPath${sep}token=$token"
            } else {
                "${baseUrl.trimEnd('/')}$cleanPath"
            }
        }
        return uri
    }
}
