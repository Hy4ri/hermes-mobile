package com.m57.hermescontrol.data.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class KnownHostsTest {
    private fun keyBlob(type: String): ByteArray {
        val typeBytes = type.toByteArray(Charsets.UTF_8)
        val length =
            byteArrayOf(
                (typeBytes.size ushr 24).toByte(),
                (typeBytes.size ushr 16).toByte(),
                (typeBytes.size ushr 8).toByte(),
                typeBytes.size.toByte(),
            )
        return length + typeBytes + byteArrayOf(1, 2, 3, 4)
    }

    private val ed25519 = keyBlob("ssh-ed25519")
    private val rsa = keyBlob("ssh-rsa")

    @Test
    fun `unknown host is not trusted`() {
        val known = KnownHosts.parse("")
        assertEquals(HostKeyStatus.UNKNOWN, known.check("gateway", "ssh-ed25519", base64(ed25519)))
    }

    @Test
    fun `accepted key matches afterwards`() {
        val known = KnownHosts.parse("").withAccepted("gateway", "ssh-ed25519", base64(ed25519))
        assertEquals(HostKeyStatus.MATCH, known.check("gateway", "ssh-ed25519", base64(ed25519)))
    }

    @Test
    fun `a different key for the same type is a change`() {
        val known = KnownHosts.parse("").withAccepted("gateway", "ssh-ed25519", base64(ed25519))
        assertEquals(HostKeyStatus.CHANGED, known.check("gateway", "ssh-ed25519", base64(rsa)))
    }

    @Test
    fun `another key type is a separate entry`() {
        val known =
            KnownHosts
                .parse("")
                .withAccepted("gateway", "ssh-ed25519", base64(ed25519))
                .withAccepted("gateway", "ssh-rsa", base64(rsa))
        assertEquals(HostKeyStatus.MATCH, known.check("gateway", "ssh-ed25519", base64(ed25519)))
        assertEquals(HostKeyStatus.MATCH, known.check("gateway", "ssh-rsa", base64(rsa)))
        assertEquals(2, known.size)
    }

    @Test
    fun `serialization round trips and skips comments and garbage`() {
        val text = "# comment\n\ngateway ssh-ed25519 ${base64(ed25519)}\nbroken line\n"
        val parsed = KnownHosts.parse(text)
        assertEquals(1, parsed.size)
        val rewritten = KnownHosts.parse(parsed.serialize())
        assertEquals(HostKeyStatus.MATCH, rewritten.check("gateway", "ssh-ed25519", base64(ed25519)))
        assertTrue(parsed.serialize().startsWith("gateway ssh-ed25519 "))
    }

    @Test
    fun `forgetting a host drops every key type for it`() {
        val known =
            KnownHosts
                .parse("")
                .withAccepted("gateway", "ssh-ed25519", base64(ed25519))
                .withAccepted("gateway", "ssh-rsa", base64(rsa))
                .withAccepted("other", "ssh-rsa", base64(rsa))
        val after = known.without("gateway")
        assertEquals(HostKeyStatus.UNKNOWN, after.check("gateway", "ssh-ed25519", base64(ed25519)))
        assertEquals(HostKeyStatus.MATCH, after.check("other", "ssh-rsa", base64(rsa)))
        assertEquals(1, after.size)
    }

    @Test
    fun `host ports and brackets are normalized preserving port per contract`() {
        // Default port 22 collapses to bare host
        assertEquals("gateway", KnownHosts.normalizeHost("gateway"))
        assertEquals("gateway", KnownHosts.normalizeHost("gateway:22"))
        assertEquals("gateway", KnownHosts.normalizeHost("[gateway]:22"))
        assertEquals("fe80::1", KnownHosts.normalizeHost("[fe80::1]:22"))
        assertEquals("fe80::1", KnownHosts.normalizeHost("fe80::1"))

        // Non-default port preserves [host]:port per contract
        assertEquals("[gateway]:2222", KnownHosts.normalizeHost("[gateway]:2222"))
        assertEquals("[gateway]:2222", KnownHosts.normalizeHost("gateway:2222"))
        assertEquals("[fe80::1]:2222", KnownHosts.normalizeHost("[fe80::1]:2222"))
    }

    @Test
    fun `identity helper produces normalized host-port identity`() {
        assertEquals("gateway", KnownHosts.identity("gateway", 22))
        assertEquals("[gateway]:2222", KnownHosts.identity("gateway", 2222))
        assertEquals("fe80::1", KnownHosts.identity("fe80::1", 22))
        assertEquals("[fe80::1]:2222", KnownHosts.identity("fe80::1", 2222))
    }

    @Test
    fun `key type comes from the first length prefixed field`() {
        assertEquals("ssh-ed25519", KnownHosts.keyType(ed25519))
        assertEquals("ssh-rsa", KnownHosts.keyType(rsa))
        assertEquals("", KnownHosts.keyType(byteArrayOf(1, 2)))
    }

    @Test
    fun `fingerprint is the openssh sha256 form`() {
        val fingerprint = KnownHosts.fingerprint(ed25519)
        assertTrue(fingerprint.startsWith("SHA256:"))
        assertTrue(!fingerprint.endsWith("="))
        assertEquals(fingerprint, KnownHosts.fingerprint(ed25519))
        assertTrue(fingerprint != KnownHosts.fingerprint(rsa))
    }

    private fun base64(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)
}
