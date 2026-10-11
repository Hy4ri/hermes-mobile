package com.m57.hermescontrol.data.remote

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Select the platform trust manager once, before any network consumer starts. */
internal object ServerTrust {
    private lateinit var policy: StartupServerTrust
    val state get() = policy.state
    val active get() = policy.active
    val manager get() = policy.manager

    fun initialize(context: Context) {
        check(!::policy.isInitialized)
        val setting = ServerTrustPreference(context.getSharedPreferences("server_trust", Context.MODE_PRIVATE))
        policy = StartupServerTrust(setting.read(), setting::write) { loadTrustManager(it) }
    }

    fun setEnabled(value: Boolean) = policy.save(value)

    internal fun loadTrustManager(
        includeUser: Boolean,
        androidStore: () -> KeyStore = { KeyStore.getInstance("AndroidCAStore").apply { load(null) } },
        factory: () -> TrustManagerFactory = {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        },
    ): X509TrustManager =
        factory()
            .apply {
                // null preserves platform defaults; AndroidCAStore delegates system + user roots to Android.
                init(if (includeUser) androidStore() else null as KeyStore?)
            }.trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()
}

/** Saved preference can change; the active policy and platform manager last for this process. */
internal class StartupServerTrust(
    val active: Boolean,
    private val persist: (Boolean) -> Unit,
    load: (Boolean) -> X509TrustManager,
) {
    val manager = load(active)
    private val saved = MutableStateFlow(active)
    val state = saved.asStateFlow()

    @Synchronized
    fun save(value: Boolean) {
        persist(value)
        saved.value = value
    }
}

/** Separate from the alias registry, whose transactional saves clear its own preferences. */
internal class ServerTrustPreference(
    private val preferences: SharedPreferences,
) {
    fun read(): Boolean = preferences.getBoolean(KEY, false)

    fun write(value: Boolean) {
        val previous = read()
        if (!preferences.edit().putBoolean(KEY, value).commit()) {
            // SharedPreferences updates memory before writing disk. Restore the prior value on failure.
            preferences.edit().putBoolean(KEY, previous).commit()
            throw IOException("Could not save server trust setting")
        }
    }

    private companion object {
        const val KEY = "trust_user_cas"
    }
}
