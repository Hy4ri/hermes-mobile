package com.m57.hermescontrol.data.remote

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean

/** Manual, origin-scoped aliases only. Key material remains in Android KeyChain. */
object ClientCertificates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var registry: CertificateBindings
    val state get() = registry.state
    private val chooserBusy = AtomicBoolean()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private lateinit var app: Context
    private val trust get() = ServerTrust.manager
    private val sockets by lazy { CertificateSocketFactory(trust, ::keyManager) }

    fun initialize(context: Context) {
        app = context.applicationContext
        val values = preferences().all
        val initial =
            values
                .mapNotNull { (key, value) ->
                    key.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let {
                        (value as? String)?.let { alias -> key to alias.ifEmpty { null } }
                    }
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
            CertificateBindings(initial, versions, persist = { bindings, revisions ->
                val editor = preferences().edit().clear()
                bindings.forEach { (key, value) -> editor.putString(key, value.orEmpty()) }
                revisions.forEach { (key, value) -> editor.putString(CACHE_PREFIX + key, value) }
                if (!editor.commit()) throw IOException("Could not save certificate binding")
            }, invalidate = sockets::invalidate)
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action == KeyChain.ACTION_KEY_ACCESS_CHANGED &&
                        intent.getBooleanExtra(KeyChain.EXTRA_KEY_ACCESSIBLE, false)
                    ) {
                        return
                    }
                    // No key/chain cache: each handshake rechecks access and certificate validity.
                    scope.launch {
                        runCatching { registry.keyChainChanged() }
                        sockets.invalidateAll()
                    }
                }
            },
            IntentFilter().apply {
                addAction(KeyChain.ACTION_KEYCHAIN_CHANGED)
                addAction(KeyChain.ACTION_KEY_ACCESS_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun preferences() = app.getSharedPreferences("client_certificate_aliases", Context.MODE_PRIVATE)

    internal fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val verifier = builder.build().hostnameVerifier
        return builder
            .sslSocketFactory(sockets, trust)
            // Preserve hostname verification while disabling cross-origin HTTP/2 coalescing.
            .hostnameVerifier { host, session -> verifier.verify(host, session) }
    }

    /** Worker only. A null alias preserves an empty binding; null URL deletes the row. */
    fun save(
        previous: HttpUrl?,
        url: HttpUrl?,
        alias: String?,
        expected: Map<String, String?>,
    ) {
        registry.save(previous, url, alias, expected)
    }

    /** Verify an explicit draft without publishing it to normal traffic or sharing TLS sessions. */
    internal suspend fun verifyCandidate(
        url: HttpUrl,
        alias: String,
    ) {
        if (!available(alias, null, null)) throw IOException("Client certificate is unavailable")
        verifyClientCertificate(
            url,
            trust,
            ClientCertificateKeyManager(
                choose = { types, issuers, _ -> alias.takeIf { available(it, types, issuers) } },
                privateKey = { selected -> if (selected == alias) key(alias) else null },
                certificateChain = { selected -> if (selected == alias) chain(alias) else null },
            ),
        )
    }

    /** Persistent generation isolates late cache responses from a previous TLS identity. */
    fun cacheKey(url: HttpUrl): String = registry.cacheKey(url)

    /** UI only. The result is a draft; this function never writes bindings. */
    fun select(
        host: Activity,
        url: HttpUrl,
        initialAlias: String?,
        result: (String?, Boolean) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val origin = requireNotNull(CertificateOrigin.from(url))
        val lifecycle = (host as? LifecycleOwner)?.lifecycle
        if (host.isFinishing || host.isDestroyed ||
            lifecycle?.currentState != Lifecycle.State.RESUMED || !chooserBusy.compareAndSet(false, true)
        ) {
            result(null, false)
            return
        }
        lateinit var observer: LifecycleEventObserver
        val session =
            CertificatePickerSession(
                schedule = { delay, action ->
                    val runnable = Runnable(action)
                    main.postDelayed(runnable, delay)
                    val cancel: () -> Unit = { main.removeCallbacks(runnable) }
                    cancel
                },
                result = { alias, valid ->
                    lifecycle.removeObserver(observer)
                    chooserBusy.set(false)
                    ExternalActivityLifecycleGuard.externalActivityReturned()
                    result(alias, valid)
                },
            )
        observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> session.paused()
                    Lifecycle.Event.ON_RESUME -> session.resumed()
                    Lifecycle.Event.ON_DESTROY -> session.finish(null, true)
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        session.launched()
        try {
            ExternalActivityLifecycleGuard.launchExternalActivity(
                acquireConnectionLease = HermesWsClient::acquireExternalActivityConnectionLease,
                releaseConnectionLease = HermesWsClient::releaseExternalActivityConnectionLease,
                prepareForBackground = { NotificationHelper.start(host) },
                cleanupAfterLaunchFailure = { NotificationHelper.stop(host) },
                launch = {
                    KeyChain.choosePrivateKeyAlias(
                        host,
                        { alias ->
                            if (session.callbackReceived()) {
                                scope.launch {
                                    val valid = alias == null || available(alias, null, null)
                                    main.post {
                                        session.finish(alias, valid)
                                    }
                                }
                            }
                        },
                        null,
                        null,
                        origin.host,
                        origin.port,
                        initialAlias,
                    )
                },
            )
        } catch (_: RuntimeException) {
            session.finish(null, false)
        }
    }

    private fun key(alias: String): PrivateKey? =
        try {
            KeyChain.getPrivateKey(app, alias)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }

    private fun chain(alias: String): Array<X509Certificate>? =
        try {
            KeyChain.getCertificateChain(app, alias)?.takeIf { it.isNotEmpty() }?.also { chain ->
                chain.forEach { it.checkValidity() }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }

    private fun available(
        alias: String,
        types: Array<out String>?,
        issuers: Array<out Principal>?,
    ): Boolean {
        val chain = chain(alias) ?: return false
        if (!types.isNullOrEmpty() &&
            types.none { it.substringBefore('_') == chain[0].publicKey.algorithm }
        ) {
            return false
        }
        if (!issuers.isNullOrEmpty() && chain.none { it.issuerX500Principal in issuers }) return false
        return key(alias) != null
    }

    private fun keyManager(origin: CertificateOrigin) =
        ClientCertificateKeyManager(
            choose = { types, issuers, _ -> registry.selected(origin) { available(it, types, issuers) } },
            privateKey = { alias -> if (state.value[origin.storageKey] == alias) key(alias) else null },
            certificateChain = { alias -> if (state.value[origin.storageKey] == alias) chain(alias) else null },
        )

    private const val CACHE_PREFIX = "cache:"
}
