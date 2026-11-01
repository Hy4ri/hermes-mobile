package com.m57.hermescontrol.data.tunnel

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class SshTunnelStoreTest {
    @BeforeEach
    fun setUp() {
        resetStoreState()
    }

    @AfterEach
    fun tearDown() {
        resetStoreState()
    }

    private fun resetStoreState() {
        SshTunnelStore.resetForTest()
    }

    @Test
    fun `encrypted preferences read failure cannot enable direct fallback`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("ssh_tunnel_configs", null) } throws SecurityException("secret ciphertext")
        val error = assertThrows<IOException> { SshTunnelStore.initialize(prefs) }
        assertEquals("Could not read SSH tunnel settings", error.message)
        assertTrue(SshTunnelStore.loadFailed)
        val blocked = assertThrows<IOException> { SshTunnelRouting.requireReady("127.0.0.1", 9119) }
        assertEquals("SSH tunnel is not connected", blocked.message)
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `stored enabled origin is protected before manager starts`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString("ssh_tunnel_configs", null) } returns
            """{"http://127.0.0.1:9119":{"enabled":true,"host":"ssh.example"}}"""
        SshTunnelStore.initialize(prefs)
        assertThrows<IOException> { SshTunnelRouting.requireReady("127.0.0.1", 9119) }
        SshTunnelRouting.requireReady("127.0.0.1", 9120)
    }

    @Test
    fun `save before initialize throws sanitized IOException`() {
        val config = SshTunnelConfig(host = "ssh.example.com", username = "root", password = "secretPassword")
        val error =
            assertThrows<IOException> {
                SshTunnelStore.save("http://127.0.0.1:9119", config)
            }
        assertFalse(error.message.orEmpty().contains("secretPassword"))
    }

    @Test
    fun `delete before initialize throws sanitized IOException`() {
        assertThrows<IOException> {
            SshTunnelStore.delete("http://127.0.0.1:9119")
        }
    }

    @Test
    fun `corrupt json setting throws sanitized IOException without credentials and sets loadFailed`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val sensitiveCorruptBlob = """{"http://127.0.0.1:9119": {"password": "superSecretValue", invalid_json}}"""
        every { prefs.getString("ssh_tunnel_configs", null) } returns sensitiveCorruptBlob

        val error =
            assertThrows<IOException> {
                SshTunnelStore.initialize(prefs)
            }

        assertFalse(error.message.orEmpty().contains("superSecretValue"))
        assertTrue(SshTunnelStore.loadFailed)
        assertTrue(SshTunnelStore.all().isEmpty())
        assertNull(SshTunnelStore.get("http://127.0.0.1:9119"))
    }

    @Test
    fun `valid encrypted prefs retains reference and parses configs successfully`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val validJson =
            """
            {
                "http://127.0.0.1:9119": {
                    "enabled": true,
                    "host": "tunnel.internal",
                    "port": 22,
                    "username": "admin",
                    "authMode": "PASSWORD",
                    "password": "secretPassword",
                    "privateKey": "",
                    "passphrase": "",
                    "remoteHost": "127.0.0.1",
                    "remotePort": 9119
                }
            }
            """.trimIndent()
        every { prefs.getString("ssh_tunnel_configs", null) } returns validJson

        SshTunnelStore.initialize(prefs)

        assertFalse(SshTunnelStore.loadFailed)

        val config = SshTunnelStore.get("http://127.0.0.1:9119")
        assertNotNull(config)
        assertEquals("tunnel.internal", config?.host)
        assertEquals("admin", config?.username)
        assertEquals("secretPassword", config?.password)
    }

    @Test
    fun `save while loadFailed protects unreadable blob and throws IOException without editing prefs`() {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val corruptBlob = """{corrupted: blob}"""
        every { prefs.getString("ssh_tunnel_configs", null) } returns corruptBlob

        assertThrows<IOException> {
            SshTunnelStore.initialize(prefs)
        }
        assertTrue(SshTunnelStore.loadFailed)

        val config = SshTunnelConfig(host = "new.host", username = "root", password = "pwd")
        assertThrows<IOException> {
            SshTunnelStore.save("http://127.0.0.1:9119", config)
        }

        verify(exactly = 0) { prefs.edit() }
    }
}
