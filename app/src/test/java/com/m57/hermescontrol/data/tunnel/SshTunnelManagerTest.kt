package com.m57.hermescontrol.data.tunnel

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SshTunnelManagerTest {
    @TempDir
    lateinit var directory: File

    private lateinit var hostsFile: File
    private val key = byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".toByteArray() + byteArrayOf(1, 2, 3, 4)
    private val config = SshTunnelConfig(host = "ssh.example", username = "user", password = "secret")
    private val target = SshTunnelManager.Target("http://gateway.internal:9119", "gateway.internal", 9119, config)

    @BeforeEach
    fun setUp() {
        SshTunnelManager.stop()
        hostsFile = File(directory, "known_hosts")
        SshTunnelManager.initialize(hostsFile)
    }

    @AfterEach
    fun tearDown() {
        SshTunnelManager.stop()
        SshTunnelManager.jschFactory = { JSch() }
    }

    private suspend fun settled(): SshTunnelState =
        withTimeout(5000) {
            SshTunnelManager.state.first { it !is SshTunnelState.Connecting && it !is SshTunnelState.Disabled }
        }

    private fun session(connect: (HostKeyRepository) -> Unit): Session {
        val jsch = mockk<JSch>()
        val session = mockk<Session>(relaxed = true)
        val repository = slot<HostKeyRepository>()
        every { jsch.getSession(any<String>(), any<String>(), any<Int>()) } returns session
        every { session.hostKeyRepository = capture(repository) } returns Unit
        every { session.connect(any<Int>()) } answers { connect(repository.captured) }
        every { session.isConnected } returns true
        every { session.setPortForwardingL(any<String>(), any<Int>(), any<String>(), any<Int>()) } answers
            { secondArg() }
        SshTunnelManager.jschFactory = { jsch }
        return session
    }

    @Test
    fun `first contact needs the exact displayed key before it is persisted`() =
        runBlocking {
            session { repository ->
                if (repository.check(config.host, key) != HostKeyRepository.OK) throw JSchException("unknown host key")
            }
            SshTunnelManager.apply(target)
            val pending = settled() as SshTunnelState.AwaitingHostKey
            assertFalse(hostsFile.exists())
            SshTunnelManager.acceptPendingHostKey(pending.host, pending.keyType, "SHA256:not-the-displayed-key")
            assertEquals(pending, SshTunnelManager.state.value)
            assertFalse(hostsFile.exists())
            SshTunnelManager.acceptPendingHostKey("different-host", pending.keyType, pending.fingerprint)
            assertFalse(hostsFile.exists())
            SshTunnelManager.acceptPendingHostKey(pending.host, "different-type", pending.fingerprint)
            assertFalse(hostsFile.exists())
            SshTunnelManager.acceptPendingHostKey(pending.host, pending.keyType, pending.fingerprint)
            withTimeout(5000) { SshTunnelManager.state.first { it is SshTunnelState.Connected } }
            assertEquals(1, KnownHosts.parse(hostsFile.readText()).size)
            assertEquals(target.host, SshTunnelRouting.current()?.host)
            assertEquals(target.origin, SshTunnelManager.activeOrigin.value)
        }

    @Test
    fun `a stopped blocking connect cannot publish state or a route later`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val session =
                session {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            every { session.disconnect() } answers { if (release.count == 0L) finished.countDown() }
            try {
                SshTunnelManager.apply(target)
                assertEquals(SshTunnelState.Connecting, SshTunnelManager.state.value)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                SshTunnelManager.stop()
                release.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                assertEquals(SshTunnelState.Disabled, SshTunnelManager.state.value)
                assertNull(SshTunnelManager.activeOrigin.value)
                assertNull(SshTunnelRouting.current())
            } finally {
                release.countDown()
            }
        }

    @Test
    fun `unreadable known hosts is a failure rather than first contact`() =
        runBlocking {
            // A directory cannot be read as a known_hosts file.
            SshTunnelManager.initialize(directory)
            SshTunnelManager.apply(target)
            assertTrue(settled() is SshTunnelState.Failed)
            assertNull(SshTunnelRouting.current())
        }
}
