package com.m57.hermescontrol.data.remote

import okhttp3.Cookie

/**
 * In-memory [CookieStore] for unit tests — no Android Keystore /
 * [EncryptedSharedPreferences] required. Mirrors the production
 * [EncryptedCookieStore] contract so the same [PersistentCookieJar] can be
 * exercised end-to-end in JVM tests (issue #470).
 */
class FakeEncryptedCookieStore : CookieStore {
    private val data = mutableMapOf<String, MutableList<CookieHolder>>()

    override suspend fun save(
        serverId: String,
        cookies: List<Cookie>,
    ) {
        data[serverId] = cookies.map { it.serialize() }.toMutableList()
    }

    override suspend fun load(serverId: String): List<Cookie> =
        data[serverId]
            ?.mapNotNull { it.toCookie() }
            .orEmpty()

    override suspend fun clear(serverId: String) {
        data.remove(serverId)
    }

    override suspend fun clearAll() {
        data.clear()
    }

    /** Test helper: inspect raw holders for a server scope. */
    fun storedHolders(serverId: String): List<CookieHolder> = data[serverId].orEmpty()
}

/**
 * Build a [PersistentCookieJar] pre-wired with a [FakeEncryptedCookieStore]
 * plus a deterministic single-threaded [kotlinx.coroutines.CoroutineScope]
 * so async persistence completes predictably inside tests.
 */
fun buildFakePersistentCookieJar(): PersistentCookieJar =
    PersistentCookieJar(
        store = FakeEncryptedCookieStore(),
        storeScope =
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.Dispatchers.Unconfined,
            ),
    )
