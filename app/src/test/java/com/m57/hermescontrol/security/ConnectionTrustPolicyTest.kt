package com.m57.hermescontrol.security

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionTrustPolicyTest {
    @Test
    fun `allows loopback private lan and tailscale`() {
        listOf(
            "localhost",
            "127.0.0.1",
            "10.42.0.5",
            "172.22.0.3",
            "192.168.178.42",
            "100.101.12.16",
            "cassy-nas.tail1234.ts.net",
            "fd7a:115c:a1e0::1",
        ).forEach { assertTrue(ConnectionTrustPolicy.allowsCleartextTo(it), it) }
    }

    @Test
    fun `blocks public cleartext destinations`() {
        listOf("example.com", "fdomain.com", "fc-public.example", "8.8.8.8", "100.63.255.255", "100.128.0.1").forEach {
            assertFalse(ConnectionTrustPolicy.allowsCleartextTo(it), it)
        }
    }
}
