package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The persistent key excludes auth generation, but queued operations retain their original owner. */
internal data class ComposerDraftKey(
    val scope: DataScope,
    val sessionId: String?,
) {
    fun persistentKey(): String =
        scope.persistentKey(
            if (sessionId == null) "draft:new" else "draft:session:${sessionId.length}:$sessionId",
        )
}

@Serializable
private data class ComposerDraftRecord(
    val key: String,
    val connectionId: String,
    val updatedAt: Long,
    val text: String,
)

/**
 * #1605: one app-owned FIFO writes encrypted drafts off the UI thread, including navigation flushes.
 * Retain at most 100 text drafts per connection for 30 days. Never truncate individual prompts.
 */
internal class ComposerDraftStore(
    prefsProvider: () -> SharedPreferences,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs by lazy(prefsProvider)
    private val json = Json { ignoreUnknownKeys = true }
    private val operations = Channel<() -> Unit>(Channel.UNLIMITED)
    private val deletedSessions = mutableSetOf<String>()
    private val revokedGenerations = mutableMapOf<String, Long>()

    init {
        scope.launch {
            for (operation in operations) operation()
        }
    }

    private fun <T> enqueue(block: () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        val queued =
            operations.trySend {
                try {
                    result.complete(block())
                } catch (failure: Exception) {
                    result.completeExceptionally(failure)
                }
            }
        if (queued.isFailure) result.completeExceptionally(IllegalStateException("Draft storage is unavailable"))
        return result
    }

    private fun isRevoked(key: ComposerDraftKey): Boolean =
        key.scope.inMemoryAuthGeneration <= (revokedGenerations[key.scope.connectionProfileId] ?: -1L) ||
            key.persistentKey() in deletedSessions

    private fun records(): List<ComposerDraftRecord> =
        prefs.getString(ROWS_KEY, null)?.let { json.decodeFromString<List<ComposerDraftRecord>>(it) } ?: emptyList()

    private fun List<ComposerDraftRecord>.unexpired(): List<ComposerDraftRecord> =
        filter { now() - it.updatedAt < RETENTION_MS }

    private fun persist(records: List<ComposerDraftRecord>) {
        check(prefs.edit().putString(ROWS_KEY, json.encodeToString(records)).commit()) {
            "Could not save composer drafts"
        }
    }

    fun load(key: ComposerDraftKey): Deferred<String?> =
        enqueue {
            if (isRevoked(key)) return@enqueue null
            val before = records()
            val current = before.unexpired()
            if (current != before) persist(current)
            current.firstOrNull { it.key == key.persistentKey() }?.text
        }

    fun save(
        key: ComposerDraftKey,
        text: String,
    ): Deferred<Unit> =
        enqueue {
            if (isRevoked(key)) return@enqueue
            val other = records().unexpired().filterNot { it.key == key.persistentKey() }
            val next =
                if (text.isBlank()) {
                    other
                } else {
                    other + ComposerDraftRecord(key.persistentKey(), key.scope.connectionProfileId, now(), text)
                }
            val retained =
                next
                    .filter { it.connectionId == key.scope.connectionProfileId }
                    .asReversed()
                    .sortedByDescending { it.updatedAt }
                    .take(MAX_DRAFTS_PER_CONNECTION)
                    .mapTo(mutableSetOf()) { it.key }
            persist(next.filter { it.connectionId != key.scope.connectionProfileId || it.key in retained })
        }

    fun rekey(
        from: ComposerDraftKey,
        to: ComposerDraftKey,
    ): Deferred<Unit> =
        enqueue {
            require(from.scope == to.scope) { "Drafts cannot move between owners" }
            if (isRevoked(from) || isRevoked(to) || from == to) return@enqueue
            val current = records().unexpired()
            val source = current.firstOrNull { it.key == from.persistentKey() } ?: return@enqueue
            persist(
                current.filterNot { it.key == from.persistentKey() || it.key == to.persistentKey() } +
                    source.copy(key = to.persistentKey()),
            )
        }

    /** A deleted session cannot be recreated by a later screen-disposal flush. */
    fun remove(key: ComposerDraftKey): Deferred<Unit> =
        enqueue {
            deletedSessions.add(key.persistentKey())
            persist(records().filterNot { it.key == key.persistentKey() })
        }

    fun clearConnection(connectionProfileId: String): Deferred<Unit> {
        // Capture before setToken(null) advances authentication; execution happens later on IO.
        val generation = AuthManager.authGenerationFlow.value
        return enqueue {
            revokedGenerations[connectionProfileId] =
                maxOf(generation, revokedGenerations[connectionProfileId] ?: -1L)
            persist(records().filterNot { it.connectionId == connectionProfileId })
        }
    }

    companion object {
        const val PREFS_NAME = "composer_drafts"
        private const val ROWS_KEY = "rows"
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_DRAFTS_PER_CONNECTION = 100

        @Volatile
        private var instance: ComposerDraftStore? = null

        fun initialized(): ComposerDraftStore? = instance

        @Suppress("DEPRECATION")
        fun get(context: Context): ComposerDraftStore =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    ComposerDraftStore(
                        prefsProvider = {
                            EncryptedSharedPreferences.create(
                                PREFS_NAME,
                                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                                app,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                            )
                        },
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    ).also { instance = it }
                }
            }
    }
}
