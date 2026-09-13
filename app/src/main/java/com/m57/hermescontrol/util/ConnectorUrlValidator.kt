package com.m57.hermescontrol.util

import java.net.URI

/**
 * Validates external connector authorization URLs.
 *
 * Enforces strict security constraints:
 * - Must use HTTPS scheme (case-insensitive).
 * - Must have a non-empty, valid host without spaces, backslashes, or control characters.
 * - Must NOT contain user-info credentials (e.g. `https://user:pass@domain`).
 * - Must NOT contain backslashes anywhere in the URI (guard against SSRF/open-redirect bypasses).
 * - Port (if specified) must be valid (1..65535).
 */
object ConnectorUrlValidator {
    private val HOST_PATTERN = Regex("""^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$""")

    fun isValidHttpsUrl(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false

        // Disallow backslashes, whitespace, and control characters anywhere in the raw URL
        for (ch in urlString) {
            if (ch == '\\' || ch.isWhitespace() || ch.isISOControl()) {
                return false
            }
        }

        return try {
            val uri = URI(urlString)
            val scheme = uri.scheme ?: return false
            if (!scheme.equals("https", ignoreCase = true)) {
                return false
            }

            val host = uri.host
            if (host.isNullOrBlank()) {
                return false
            }

            // Userinfo is strictly prohibited (e.g. credentials embedded in URL)
            if (uri.rawUserInfo != null) {
                return false
            }

            // Check host structure (must match domain / alphanumeric pattern or valid IP)
            if (!HOST_PATTERN.matches(host)) {
                return false
            }

            // Verify port if explicitly provided
            if (uri.port != -1 && (uri.port < 1 || uri.port > 65535)) {
                return false
            }

            true
        } catch (_: Exception) {
            false
        }
    }
}
