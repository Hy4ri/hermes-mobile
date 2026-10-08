package com.m57.hermescontrol.data.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.http.X509TrustManagerExtensions
import android.security.KeyChain
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Global server authentication policy, initialized synchronously before any network consumer. */
internal object ServerTrust {
    private val enabled = MutableStateFlow(false)
    val state = enabled.asStateFlow()
    private lateinit var policy: ReloadableServerTrust
    private var storeVersion: List<String>? = null

    val manager: X509TrustManager = AndroidServerTrustManager { policy }

    fun initialize(context: Context) {
        val app = context.applicationContext
        val setting = ServerTrustPreference(app.getSharedPreferences("server_trust", Context.MODE_PRIVATE))
        val initial = setting.read()
        policy =
            ReloadableServerTrust(
                initial = initial,
                load = ::loadTrustManager,
                persist = setting::write,
                changed = OkHttpProvider::evictConnections,
            )
        enabled.value = initial
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    synchronized(this@ServerTrust) {
                        storeVersion = null
                        policy.reload()
                    }
                }
            },
            IntentFilter(KeyChain.ACTION_TRUST_STORE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        policy.setEnabled(value)
        enabled.value = policy.isEnabled()
    }

    /** Also catches changes while stopped/backgrounded, before cache hits and pooled requests. */
    @Synchronized
    fun refresh() {
        try {
            val store = androidStore()
            val digest = MessageDigest.getInstance("SHA-256")
            val version =
                store.aliases().toList().sorted().map { alias ->
                    val fingerprint =
                        Base64.getEncoder().encodeToString(
                            digest.digest(store.getCertificate(alias).encoded),
                        )
                    "$alias:$fingerprint"
                }
            if (version != storeVersion) {
                if (!policy.reload()) throw IOException("Could not reload server trust")
                storeVersion = version
            }
        } catch (e: Exception) {
            storeVersion = null
            policy.reload()
            throw IOException("Could not refresh Android CA store", e)
        }
    }

    fun cacheEpoch(): String {
        refresh()
        return policy.cacheEpoch()
    }

    fun track(factory: CertificateSocketFactory) = policy.track(factory)

    fun untrack(factory: CertificateSocketFactory) = policy.untrack(factory)

    val interceptor =
        Interceptor { chain ->
            val epoch = cacheEpoch()
            val response = chain.proceed(chain.request())
            try {
                policy.requireEpoch(epoch)
                response
            } catch (e: IOException) {
                response.close()
                throw e
            }
        }

    private fun androidStore(): KeyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }

    internal fun loadTrustManager(includeUser: Boolean): X509TrustManager =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply {
                // null preserves the app's original Android network-security-config system trust rules.
                // AndroidCAStore exposes currently active system + user roots (including updated system roots).
                init(if (includeUser) androidStore() else null as KeyStore?)
            }.trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()

    /** X509TrustManagerExtensions discovers this overload reflectively; keep it in release builds. */
    @Keep
    class AndroidServerTrustManager(
        private val current: () -> ReloadableServerTrust,
    ) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = current().acceptedIssuers

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = current().checkClientTrusted(chain, authType)

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) = current().checkServerTrusted(chain, authType)

        @Suppress("unused")
        fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            host: String,
        ): List<X509Certificate> =
            current().checked { X509TrustManagerExtensions(it).checkServerTrusted(chain, authType, host) }
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
