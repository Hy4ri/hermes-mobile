package com.m57.hermescontrol.data.tunnel

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var knownHosts: KnownHostsFile? = null

    private var job: Job? = null
    private var target: Target? = null
    private var activeSession: Session? = null
    private var pendingHostKey: PresentedHostKey? = null

    fun initialize(context: Context) {
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
        pendingHostKey = null
        this.target = target
        job = scope.launch { supervise(target) }
    }

    /** Stops the tunnel and clears the route. */
    @Synchronized
    fun stop() {
        stopLocked()
    }

    /** Trusts the key offered on first contact and reconnects. */
    @Synchronized
    fun acceptPendingHostKey() {
        val pending = pendingHostKey ?: return
        val current = target ?: return
        val file = knownHosts ?: return
        runCatching { file.accept(pending) }
        pendingHostKey = null
        job?.cancel()
        job = scope.launch { supervise(current) }
    }

    /** Drops every stored key for the SSH host, so the next connect asks again. */
    @Synchronized
    fun forgetHostKey(host: String) {
        val file = knownHosts ?: return
        runCatching { file.forget(host) }
        val current = target ?: return
        if (current.config.host.equals(host, ignoreCase = true)) {
            pendingHostKey = null
            job?.cancel()
            job = scope.launch { supervise(current) }
        }
    }

    private fun stopLocked() {
        val session = activeSession
        job?.cancel()
        job = null
        target = null
        activeSession = null
        SshTunnelRouting.activate(null)
        runCatching { session?.disconnect() }
        _state.value = SshTunnelState.Disabled
    }

    private suspend fun supervise(target: Target) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = SshTunnelState.Connecting
            val running =
                try {
                    connect(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val failure = failureOf(e)
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
                            attempt++
                            delay(backoffFor(attempt))
                            continue
                        }
                    }
                }

            _state.value = SshTunnelState.Connected(running.localPort)
            SshTunnelRouting.activate(SshTunnelRoute(target.host, target.port, running.localPort))
            attempt = 0
            while (currentCoroutineContext().isActive && running.session.isConnected) {
                delay(1_000)
            }
            SshTunnelRouting.activate(null)
            runCatching { running.session.disconnect() }
            if (currentCoroutineContext().isActive) {
                _state.value = SshTunnelState.Connecting
                delay(MIN_BACKOFF_MS)
            }
        }
    }

    private fun connect(target: Target): Running {
        val file = knownHosts ?: error("SshTunnelManager is not initialized")
        val config = target.config
        pendingHostKey = null
        val repository =
            JschHostKeyRepository(file.read()) { presented ->
                pendingHostKey = presented
            }
        val jsch = JSch()
        if (config.authMode == SshAuthMode.PRIVATE_KEY) {
            val passphrase = config.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity("hermes-mobile-tunnel", config.privateKey.toByteArray(Charsets.UTF_8), null, passphrase)
        }
        val session = jsch.getSession(config.username, config.host, config.port)
        if (config.authMode == SshAuthMode.PASSWORD) {
            session.setPassword(config.password.toByteArray(Charsets.UTF_8))
            session.userInfo = PasswordUserInfo(config.password)
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        } else {
            session.setConfig("PreferredAuthentications", "publickey")
        }
        session.setConfig("StrictHostKeyChecking", "yes")
        session.hostKeyRepository = repository
        session.serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
        session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
        session.timeout = CONNECT_TIMEOUT_MS
        activeSession = session
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            val localPort = forward(session, config)
            return Running(session, localPort)
        } catch (e: Throwable) {
            runCatching { session.disconnect() }
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

    /** Answers keyboard-interactive password prompts with the saved password. */
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
    fun read(): KnownHosts =
        KnownHosts.parse(
            runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty(),
        )

    fun accept(presented: PresentedHostKey) {
        write(read().withAccepted(presented.host, presented.keyType, presented.keyBase64))
    }

    fun forget(host: String) {
        write(read().without(host))
    }

    private fun write(knownHosts: KnownHosts) {
        file.parentFile?.mkdirs()
        file.writeText(knownHosts.serialize())
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
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
        // JSch passes the raw host key blob (not its base64 text); known_hosts
        // stores the base64 form, exactly like OpenSSH does.
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
