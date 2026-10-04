package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

/** Transactional alias registry independent of connection profiles and Android chooser lifetime. */
internal class CertificateBindings(
    initial: Map<String, String?> = emptyMap(),
    versions: Map<String, String> = emptyMap(),
    private val persist: (Map<String, String?>, Map<String, String>) -> Unit,
    private val invalidate: (CertificateOrigin) -> Unit,
) {
    private val bindings = MutableStateFlow(initial.toMap())
    val state = bindings.asStateFlow()
    private var cacheVersions = versions.toMap()
    private val processEpoch = UUID.randomUUID().toString()

    @Synchronized
    fun save(
        previous: HttpUrl?,
        url: HttpUrl?,
        alias: String?,
        expected: Map<String, String?>,
    ) {
        val old = previous?.let { requireNotNull(CertificateOrigin.from(it)) }
        val next = url?.let { requireNotNull(CertificateOrigin.from(it)) }
        check(bindings.value == expected) { "Certificate bindings changed; reopen the editor" }
        require(next == null || next == old || next.storageKey !in bindings.value) {
            "A binding already exists for this host and port"
        }
        val updated = bindings.value.toMutableMap()
        old?.let { updated.remove(it.storageKey) }
        next?.let { updated[it.storageKey] = alias }
        val affected = listOfNotNull(old, next).distinct()
        val versions =
            cacheVersions + affected.associate { it.storageKey to UUID.randomUUID().toString() } +
                (GLOBAL_CACHE_VERSION to UUID.randomUUID().toString())
        persist(updated, versions)
        cacheVersions = versions
        bindings.value = updated.toMap()
        affected.forEach(invalidate)
    }

    @Synchronized
    fun cacheKey(url: HttpUrl): String {
        val origin = CertificateOrigin.from(url)
        val version = origin?.let { cacheVersions[it.storageKey] }.orEmpty()
        // Redirect destinations are unknown before fetching, so every remote cache also has a shared epoch.
        val key = "$url#mtls=$version/${cacheVersions[GLOBAL_CACHE_VERSION].orEmpty()}"
        // KeyChain changes while the process was stopped are never observed, so persisted generations cannot
        // vouch for the installed identity. With bindings, remote caches are valid only for this process.
        return if (bindings.value.isEmpty()) key else "$key/$processEpoch"
    }

    /** KeyChain removal/revocation retires credentials without discarding the user's saved aliases. */
    @Synchronized
    fun keyChainChanged() {
        val origins = bindings.value.keys.map { CertificateOrigin.from(it.toHttpUrl())!! }
        val versions =
            cacheVersions + origins.associate { it.storageKey to UUID.randomUUID().toString() } +
                (GLOBAL_CACHE_VERSION to UUID.randomUUID().toString())
        try {
            persist(bindings.value, versions)
        } finally {
            // Even a persistence failure must retire in-process TLS sessions and caches.
            cacheVersions = versions
            origins.forEach(invalidate)
        }
    }

    private companion object {
        const val GLOBAL_CACHE_VERSION = "*"
    }

    /** Validate saved keys outside the registry lock; a concurrent edit cannot return an old identity. */
    fun selected(
        origin: CertificateOrigin,
        available: (String) -> Boolean,
    ): String? {
        val (alias, version) =
            synchronized(this) {
                bindings.value[origin.storageKey] to cacheVersions[origin.storageKey]
            }
        if (alias == null || !available(alias)) return null
        return synchronized(this) {
            alias.takeIf { bindings.value[origin.storageKey] == it && cacheVersions[origin.storageKey] == version }
        }
    }
}
