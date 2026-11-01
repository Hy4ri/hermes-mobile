package com.m57.hermescontrol.data.tunnel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.m57.hermescontrol.data.remote.requiresLocalNetworkPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64

/** Public tunnel state, rendered by the login and connection screens. */
sealed interface SshTunnelState {
    data object Disabled : SshTunnelState

    data object Connecting : SshTunnelState

    data class Connected(
        val localPort: Int,
    ) : SshTunnelState

    /** First contact with an unknown host key: nothing is trusted until the user accepts it. */
    data class AwaitingHostKey(
        val host: String,
        val keyType: String,
        val fingerprint: String,
    ) : SshTunnelState

    data class Failed(
        val reason: SshTunnelFailure,
    ) : SshTunnelState
}

/** Why a tunnel is not up. Mapped to strings by the UI. */
enum class SshTunnelFailure {
    UNREACHABLE,
    AUTHENTICATION,
    HOST_KEY_CHANGED,
    FORWARDING,
    UNKNOWN,
}

/** A host key presented during the first connection to an SSH server. */
internal data class PresentedHostKey(
    val host: String,
    val keyType: String,
    val keyBase64: String,
    val fingerprint: String,
)

/**
 * Owns the SSH session that carries one dashboard origin's traffic.
 *
 * A single supervisor coroutine connects, publishes the local forward as
 * [SshTunnelRoute], watches the session and reconnects with backoff. Host keys
 * are trusted on first use only: an unknown key stops the tunnel in
 * [SshTunnelState.AwaitingHostKey] until [acceptPendingHostKey] persists it, and
 * a *changed* key is a hard failure that only [forgetHostKey] clears.
 */
object SshTunnelManager {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val SERVER_ALIVE_INTERVAL_MS = 15_000
    private const val SERVER_ALIVE_COUNT_MAX = 3
    private const val MIN_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val KNOWN_HOSTS_FILE = "ssh_known_hosts"
    private const val LOCAL_HOST = "127.0.0.1"
    private const val PORT_ATTEMPTS = 3

    /** One dashboard origin plus the settings that reach it. */
    data class Target(
        val origin: String,
        val host: String,
        val port: Int,
        val config: SshTunnelConfig,
    ) {
        fun changeToken(): String = "$origin|$host|$port|${config.changeToken()}"
    }

    private val _state = MutableStateFlow<SshTunnelState>(SshTunnelState.Disabled)
    val state: StateFlow<SshTunnelState> = _state.asStateFlow()

    private val _activeOrigin = MutableStateFlow<String?>(null)
    val activeOrigin: StateFlow<String?> = _activeOrigin.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var knownHosts: KnownHostsFile? = null

    @VisibleForTesting
    internal var jschFactory: () -> JSch = { JSch() }

    private var job: Job? = null
    private var currentGen: Long = 0L
    private var target: Target? = null
    private var activeSession: Session? = null
    private var pendingHostKey: PresentedHostKey? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        initialize(File(context.filesDir, KNOWN_HOSTS_FILE))
    }

    /** JVM-testable seam: the tunnel only needs a file for its host keys. */
    @VisibleForTesting
    internal fun initialize(file: File) {
        knownHosts = KnownHostsFile(file)
    }

    /**
     * Makes the running tunnel match [target], or stops it when [target] is
     * null. Safe to call on every connection sync: an unchanged, healthy
     * tunnel is left alone.
     */
    @Synchronized
    fun apply(target: Target?) {
        if (target == null) {
            stopLocked()
            return
        }
        val running = job?.isActive == true
        val healthy = _state.value is SshTunnelState.Connected || _state.value is SshTunnelState.Connecting
        if (running && healthy && this.target?.changeToken() == target.changeToken()) return

        stopLocked()
        val generation = ++currentGen
        this.target = target
        this.pendingHostKey = null
        _activeOrigin.value = target.origin
        _state.value = SshTunnelState.Connecting

        // Configure parent routing synchronously before launching connect
        SshTunnelRouting.configure(target.host, target.port)

        job = scope.launch { supervise(target, generation) }
    }

    /** Stops the tunnel and clears the route. */
    @Synchronized
    fun stop() {
        stopLocked()
    }

    /**
     * Trusts the key offered on first contact and reconnects.
     * Validates all presented fields against the pending host key under synchronization.
     * File write happens asynchronously on the IO scope without holding the manager lock;
     * stale generations are fenced before reconnect.
     */
    @Synchronized
    fun acceptPendingHostKey(
        host: String,
        keyType: String,
        fingerprint: String,
    ) {
        val pending = pendingHostKey ?: return
        if (pending.host != host || pending.keyType != keyType || pending.fingerprint != fingerprint) {
            return
        }
        val current = target ?: return
        val file = knownHosts ?: return

        val captureGen = ++currentGen
        val oldSession = activeSession
        activeSession = null
        pendingHostKey = null
        runCatching { oldSession?.disconnect() }
        job?.cancel()

        _state.value = SshTunnelState.Connecting

        job =
            scope.launch {
                try {
                    file.accept(pending)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    synchronized(SshTunnelManager) {
                        if (captureGen == currentGen) {
                            _state.value = SshTunnelState.Failed(SshTunnelFailure.UNKNOWN)
                        }
                    }
                    return@launch
                }

                synchronized(SshTunnelManager) {
                    if (captureGen != currentGen) return@launch
                }
                supervise(current, captureGen)
            }
    }

    /**
     * Drops stored keys for the SSH host and port identity, so the next connect asks again.
     * Identity for port 22 is the bare host; for non-default ports it is `[host]:port`.
     */
    @Synchronized
    fun forgetHostKey(
        host: String,
        port: Int,
    ) {
        val file = knownHosts ?: return
        val identity = KnownHosts.identity(host, port)

        val current = target
        val matchesTarget =
            current != null &&
                current.config.host.equals(host, ignoreCase = true) &&
                current.config.port == port
        val captureGen = if (matchesTarget) ++currentGen else currentGen
        if (matchesTarget) {
            val oldSession = activeSession
            activeSession = null
            pendingHostKey = null
            job?.cancel()
            SshTunnelRouting.activate(null)
            runCatching { oldSession?.disconnect() }
            _state.value = SshTunnelState.Connecting
        }
        val forgetting =
            scope.launch {
                try {
                    file.forget(identity)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    synchronized(SshTunnelManager) {
                        if (matchesTarget && captureGen == currentGen) {
                            _state.value = SshTunnelState.Failed(SshTunnelFailure.UNKNOWN)
                        }
                    }
                    return@launch
                }
                synchronized(SshTunnelManager) {
                    if (captureGen != currentGen) return@launch
                }
                if (matchesTarget) supervise(current, captureGen)
            }
        if (matchesTarget) job = forgetting
    }

    private fun stopLocked() {
        currentGen++
        val session = activeSession
        job?.cancel()
        job = null
        target = null
        activeSession = null
        pendingHostKey = null
        _activeOrigin.value = null

        // Parent routing contract: configure(null) plus activate(null)
        SshTunnelRouting.configure(null, 0)
        SshTunnelRouting.activate(null)

        runCatching { session?.disconnect() }
        _state.value = SshTunnelState.Disabled
    }

    private suspend fun supervise(
        target: Target,
        generation: Long,
    ) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            synchronized(this) {
                if (generation != currentGen) return
                _state.value = SshTunnelState.Connecting
            }

            val running =
                try {
                    connect(target, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val failure = failureOf(e)
                    synchronized(this) {
                        if (generation != currentGen) return
                        val presented = pendingHostKey
                        when {
                            failure == SshTunnelFailure.HOST_KEY_CHANGED -> {
                                _state.value = SshTunnelState.Failed(failure)
                                return
                            }

                            failure == SshTunnelFailure.UNREACHABLE && presented != null -> {
                                _state.value =
                                    SshTunnelState.AwaitingHostKey(
                                        host = presented.host,
                                        keyType = presented.keyType,
                                        fingerprint = presented.fingerprint,
                                    )
                                return
                            }

                            else -> {
                                _state.value = SshTunnelState.Failed(failure)
                            }
                        }
                    }
                    attempt++
                    delay(backoffFor(attempt))
                    continue
                }

            try {
                synchronized(this) {
                    if (generation != currentGen) {
                        runCatching { running.session.disconnect() }
                        return
                    }
                    SshTunnelRouting.activate(SshTunnelRoute(target.host, target.port, running.localPort))
                    _state.value = SshTunnelState.Connected(running.localPort)
                }

                attempt = 0
                while (currentCoroutineContext().isActive && running.session.isConnected) {
                    delay(1_000)
                }
            } finally {
                synchronized(this) {
                    if (generation == currentGen) {
                        SshTunnelRouting.activate(null)
                        if (activeSession === running.session) {
                            activeSession = null
                        }
                    }
                }
                runCatching { running.session.disconnect() }
            }

            if (currentCoroutineContext().isActive) {
                synchronized(this) {
                    if (generation != currentGen) return
                    _state.value = SshTunnelState.Connecting
                }
                delay(MIN_BACKOFF_MS)
            }
        }
    }

    private fun connect(
        target: Target,
        generation: Long,
    ): Running {
        val file = knownHosts ?: error("SshTunnelManager is not initialized")
        val config = target.config
        // Every dial, including automatic reconnects, checks permission on the IO thread.
        val context = appContext
        if (context != null && Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED &&
            requiresLocalNetworkPermission(Build.VERSION.SDK_INT, target.origin, config.host)
        ) {
            throw JSchException("Local network permission is required")
        }

        synchronized(this) {
            if (generation != currentGen) throw CancellationException("Stale generation")
            pendingHostKey = null
        }

        val repository =
            JschHostKeyRepository(file.read()) { presented ->
                synchronized(this) {
                    if (generation == currentGen) {
                        pendingHostKey = presented
                    }
                }
            }

        val jsch = jschFactory()
        if (config.authMode == SshAuthMode.PRIVATE_KEY) {
            val passphrase = config.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("hermes-mobile-tunnel", config.privateKey.toByteArray(Charsets.UTF_8), null, passphrase)
        }
        val session = jsch.getSession(config.username, config.host, config.port)
        if (config.authMode == SshAuthMode.PASSWORD) {
            session.setPassword(config.password.toByteArray(Charsets.UTF_8))
            session.userInfo = PasswordUserInfo(config.password)
            session.setConfig("PreferredAuthentications", "password")
        } else {
            session.setConfig("PreferredAuthentications", "publickey")
        }
        session.setConfig("StrictHostKeyChecking", "yes")
        session.hostKeyRepository = repository
        session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
        session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
        session.timeout = CONNECT_TIMEOUT_MS

        synchronized(this) {
            if (generation != currentGen) {
                runCatching { session.disconnect() }
                throw CancellationException("Stale generation")
            }
            activeSession = session
        }

        try {
            session.connect(CONNECT_TIMEOUT_MS)
            val localPort = forward(session, config)
            synchronized(this) {
                if (generation != currentGen) {
                    runCatching { session.disconnect() }
                    throw CancellationException("Stale generation")
                }
            }
            return Running(session, localPort)
        } catch (e: Throwable) {
            runCatching { session.disconnect() }
            synchronized(this) {
                if (activeSession === session) {
                    activeSession = null
                }
            }
            if (repository.lastStatus == HostKeyStatus.CHANGED) throw HostKeyChangedException()
            throw e
        }
    }

    /**
     * Binds the local end of the forward on a free loopback port. JSch can pick
     * one itself, but an explicit port makes the failure (a port taken between
     * reservation and bind) retryable instead of fatal.
     */
    private fun forward(
        session: Session,
        config: SshTunnelConfig,
    ): Int {
        var last: Throwable? = null
        repeat(PORT_ATTEMPTS) {
            val candidate = freeLoopbackPort()
            try {
                session.setPortForwardingL(LOCAL_HOST, candidate, config.remoteHost, config.remotePort)
                return candidate
            } catch (e: JSchException) {
                last = e
            }
        }
        throw ForwardException(last)
    }

    private fun freeLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun failureOf(error: Throwable): SshTunnelFailure =
        when {
            error is HostKeyChangedException -> SshTunnelFailure.HOST_KEY_CHANGED
            error is ForwardException -> SshTunnelFailure.FORWARDING
            error is JSchException && error.isAuthenticationFailure() -> SshTunnelFailure.AUTHENTICATION
            error is JSchException -> SshTunnelFailure.UNREACHABLE
            else -> SshTunnelFailure.UNKNOWN
        }

    private fun JSchException.isAuthenticationFailure(): Boolean {
        val text = message.orEmpty().lowercase()
        return "auth" in text || "publickey" in text || "password" in text
    }

    private fun backoffFor(attempt: Int): Long =
        minOf(
            MAX_BACKOFF_MS,
            MIN_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 5)),
        )

    private data class Running(
        val session: Session,
        val localPort: Int,
    )

    private class HostKeyChangedException : Exception("SSH host key changed")

    private class ForwardException(
        cause: Throwable?,
    ) : Exception("Could not open the SSH forward", cause)

    /** Supplies the saved password for standard password authentication. */
    private class PasswordUserInfo(
        private val password: String,
    ) : UserInfo {
        override fun getPassphrase(): String? = null

        override fun getPassword(): String = password

        override fun promptPassword(message: String?): Boolean = true

        override fun promptPassphrase(message: String?): Boolean = false

        override fun promptYesNo(message: String?): Boolean = false

        override fun showMessage(message: String?) = Unit
    }
}

/** Reads and writes the `known_hosts` file kept in app-private storage. */
internal class KnownHostsFile(
    private val file: File,
) {
    @Synchronized
    fun read(): KnownHosts {
        if (!file.exists()) return KnownHosts.parse("")
        val content =
            try {
                file.readText()
            } catch (e: IOException) {
                throw IOException("Could not read known_hosts file: ${e.message}", e)
            }
        return KnownHosts.parse(content)
    }

    @Synchronized
    fun accept(presented: PresentedHostKey) {
        write(read().withAccepted(presented.host, presented.keyType, presented.keyBase64))
    }

    @Synchronized
    fun forget(identity: String) {
        write(read().without(identity))
    }

    private fun write(knownHosts: KnownHosts) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create directory for known_hosts: ${parent.absolutePath}")
        }
        val temp = File.createTempFile("known_hosts", ".tmp", parent ?: file.absoluteFile.parentFile)
        try {
            FileOutputStream(temp).use { fos ->
                fos.write(knownHosts.serialize().toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            temp.setReadable(false, false)
            temp.setWritable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(true, true)

            if (!temp.renameTo(file)) throw IOException("Could not replace known_hosts file")
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        } catch (e: Throwable) {
            temp.delete()
            throw IOException("Could not atomically write known_hosts: ${e.message}", e)
        }
    }
}

/**
 * Compares a presented host key with the stored entries. The first contact is
 * captured for the confirmation dialog; nothing is trusted implicitly.
 */
private class JschHostKeyRepository(
    private val knownHosts: KnownHosts,
    private val onUnknownKey: (PresentedHostKey) -> Unit,
) : HostKeyRepository {
    var lastStatus: HostKeyStatus? = null
        private set

    override fun check(
        host: String,
        key: ByteArray,
    ): Int {
        // JSch passes the host as passed to getSession or "[host]:port" if nondefault port.
        // We match on host identity preserved by KnownHosts.
        val type = KnownHosts.keyType(key)
        val base64 = Base64.getEncoder().encodeToString(key)
        val status = knownHosts.check(host, type, base64)
        lastStatus = status
        if (status == HostKeyStatus.UNKNOWN && type.isNotEmpty()) {
            onUnknownKey(PresentedHostKey(host, type, base64, KnownHosts.fingerprint(key)))
        }
        return when (status) {
            HostKeyStatus.MATCH -> HostKeyRepository.OK
            HostKeyStatus.CHANGED -> HostKeyRepository.CHANGED
            HostKeyStatus.UNKNOWN -> HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(
        hostkey: HostKey,
        ui: UserInfo?,
    ) = Unit

    override fun remove(
        host: String,
        type: String?,
    ) = Unit

    override fun remove(
        host: String,
        type: String?,
        key: ByteArray?,
    ) = Unit

    override fun getKnownHostsRepositoryID(): String = "hermes-mobile"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(
        host: String?,
        type: String?,
    ): Array<HostKey> = emptyArray()
}
