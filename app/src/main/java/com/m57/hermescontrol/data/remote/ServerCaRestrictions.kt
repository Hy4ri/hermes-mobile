package com.m57.hermescontrol.data.remote

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import javax.net.ssl.X509TrustManager

/** Server CA bindings are independent from both profiles and the existing KeyChain client aliases. */
internal object ServerCaRestrictions {
    private lateinit var registry: CertificateBindings
    val state get() = registry.state
    val store = AndroidCaStore()

    fun initialize(context: Context) {
        val preferences =
            context.applicationContext.getSharedPreferences(
                "server_ca_restrictions",
                Context.MODE_PRIVATE,
            )
        val values = preferences.all
        val bindings =
            values
                .mapNotNull { (key, value) ->
                    key.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let { key to (value as? String) }
                }.toMap()
        val versions =
            values
                .mapNotNull { (key, value) ->
                    if (key.startsWith(CACHE_PREFIX) &&
                        value is String
                    ) {
                        key.removePrefix(CACHE_PREFIX) to value
                    } else {
                        null
                    }
                }.toMap()
        registry =
            CertificateBindings(bindings, versions, persist = { updated, revisions ->
                val previous = preferences.all
                val editor = preferences.edit().clear()
                updated.forEach { (key, value) -> editor.putString(key, value.orEmpty()) }
                revisions.forEach { (key, value) -> editor.putString(CACHE_PREFIX + key, value) }
                if (!editor.commit()) {
                    // A failed disk write must not change the in-memory persisted restriction on restart.
                    val restore = preferences.edit().clear()
                    previous.forEach { (key, value) ->
                        // Invalid stored selections must remain fail-closed after a failed edit.
                        restore.putString(key, (value as? String).orEmpty())
                    }
                    restore.commit()
                    throw IOException("Could not save CA restriction")
                }
            }, invalidate = ClientCertificates::invalidateServerCa)
    }

    fun save(
        previous: HttpUrl?,
        url: HttpUrl?,
        reference: CaReference?,
        expected: Map<String, String?>,
    ) {
        if (url != null) store.resolve(requireNotNull(reference))
        registry.save(previous, url, reference?.encode(), expected)
    }

    fun manager(origin: CertificateOrigin): X509TrustManager =
        OriginCaTrustManager(
            ServerTrust.manager,
            registry,
            origin,
            selected = { store.trust(CaReference.decode(it), ServerTrust.active) },
        )

    /** Check store identity even when a new socket resumes an existing TLS session. */
    fun checkAvailable(origin: CertificateOrigin) {
        registry.checkedBinding(origin) { present, reference ->
            if (present) {
                try {
                    val selected = CaReference.decode(requireNotNull(reference))
                    store.resolveAllowed(selected, ServerTrust.active)
                } catch (e: Exception) {
                    throw java.security.cert.CertificateException("Selected Android CA is unavailable", e)
                }
            }
        }
    }

    fun cacheKey(url: HttpUrl): String = registry.cacheKey(url)

    private const val CACHE_PREFIX = "cache:"
}
