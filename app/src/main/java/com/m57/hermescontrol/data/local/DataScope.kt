package com.m57.hermescontrol.data.local

/**
 * Canonical data-scope identity representing the authenticated server and profile context.
 * Used to partition caches (both in-memory and persistent) so that switching servers,
 * changing server URLs, switching server-side profiles, or logging out/re-logging in
 * never leaks data across boundaries.
 *
 * Contains no credentials, tokens, cookies, passwords, or secrets.
 */
data class DataScope(
    val connectionProfileId: String,
    val baseUrl: String,
    val activeProfileId: String,
    val inMemoryAuthGeneration: Long = 0L,
) {
    /**
     * Deterministic in-memory cache key.
     * Uses length-prefixing for unambiguous separation of components, and includes
     * [inMemoryAuthGeneration] so logging out and re-logging in automatically invalidates
     * all in-memory caches.
     */
    fun inMemoryKey(localKey: String = "default"): String = "g$inMemoryAuthGeneration:${persistentKey(localKey)}"

    /**
     * Deterministic persistent cache key for disk storage (e.g. SessionListCacheStore).
     * Uses length-prefixing so no delimiter collision is possible.
     * Does NOT include volatile [inMemoryAuthGeneration] so disk caches survive normal process restarts.
     * Disk caches are explicitly wiped on logout.
     */
    fun persistentKey(localKey: String = "default"): String =
        "c${connectionProfileId.length}:$connectionProfileId:u${baseUrl.length}:$baseUrl:p${activeProfileId.length}:$activeProfileId:k${localKey.length}:$localKey"

    fun scopedKey(localKey: String = "default"): String = inMemoryKey(localKey)

    companion object {
        val EMPTY =
            DataScope(
                connectionProfileId = AuthManager.DEFAULT_PROFILE_ID,
                baseUrl = "",
                activeProfileId = AuthManager.DEFAULT_PROFILE_ID,
                inMemoryAuthGeneration = 0L,
            )
    }
}
