package com.m57.hermescontrol.data.local

/**
 * Canonical data-scope identity representing the authenticated server and profile context.
 * Used to partition caches (both in-memory and persistent) so that switching servers
 * or server-side profiles never leaks data across boundaries.
 *
 * Contains no credentials or secrets.
 */
data class DataScope(
    val connectionProfileId: String,
    val baseUrl: String,
    val activeProfileId: String,
) {
    /**
     * Canonical string key for cache indexing.
     * Guaranteed deterministic: connection + server URL + active profile.
     */
    val key: String get() = "$connectionProfileId|$baseUrl|$activeProfileId"

    fun scopedKey(localKey: String): String = "$key:$localKey"

    companion object {
        val EMPTY =
            DataScope(
                connectionProfileId = AuthManager.DEFAULT_PROFILE_ID,
                baseUrl = "",
                activeProfileId = AuthManager.DEFAULT_PROFILE_ID,
            )
    }
}
