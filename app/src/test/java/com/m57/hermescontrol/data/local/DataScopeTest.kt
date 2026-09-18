package com.m57.hermescontrol.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataScopeTest {
    @Test
    fun testSameScopeProducesSameKeys() {
        val scope1 = DataScope("default", "http://192.168.1.57:9119", "main", 0L)
        val scope2 = DataScope("default", "http://192.168.1.57:9119", "main", 0L)

        assertEquals(scope1, scope2)
        assertEquals(scope1.inMemoryKey("sessions"), scope2.inMemoryKey("sessions"))
        assertEquals(scope1.persistentKey("sessions"), scope2.persistentKey("sessions"))
    }

    @Test
    fun testDifferentConnectionProducesDifferentKeys() {
        val scope1 = DataScope("conn-a", "http://192.168.1.57:9119", "main", 0L)
        val scope2 = DataScope("conn-b", "http://192.168.1.57:9119", "main", 0L)

        assertNotEquals(scope1.inMemoryKey(), scope2.inMemoryKey())
        assertNotEquals(scope1.persistentKey(), scope2.persistentKey())
    }

    @Test
    fun testChangedBaseUrlProducesDifferentKeys() {
        val scope1 = DataScope("default", "http://192.168.1.57:9119", "main", 0L)
        val scope2 = DataScope("default", "http://10.0.0.2:9119", "main", 0L)

        assertNotEquals(scope1.inMemoryKey(), scope2.inMemoryKey())
        assertNotEquals(scope1.persistentKey(), scope2.persistentKey())
    }

    @Test
    fun testDifferentActiveProfileProducesDifferentKeys() {
        val scope1 = DataScope("default", "http://192.168.1.57:9119", "profile-1", 0L)
        val scope2 = DataScope("default", "http://192.168.1.57:9119", "profile-2", 0L)

        assertNotEquals(scope1.inMemoryKey(), scope2.inMemoryKey())
        assertNotEquals(scope1.persistentKey(), scope2.persistentKey())
    }

    @Test
    fun testAuthGenerationInvalidatesInMemoryKeyOnly() {
        val scope1 = DataScope("default", "http://192.168.1.57:9119", "main", 0L)
        val scope2 = DataScope("default", "http://192.168.1.57:9119", "main", 1L)

        assertNotEquals(scope1.inMemoryKey("default"), scope2.inMemoryKey("default"))
        assertEquals(scope1.persistentKey("default"), scope2.persistentKey("default"))
    }

    @Test
    fun testLengthPrefixPreventsDelimiterInjection() {
        val scopeA = DataScope("c:1", "http://url", "p", 0L)
        val scopeB = DataScope("c", "1:http://url", "p", 0L)

        assertNotEquals(scopeA.persistentKey("k"), scopeB.persistentKey("k"))
        assertNotEquals(scopeA.inMemoryKey("k"), scopeB.inMemoryKey("k"))
    }

    @Test
    fun testNoSecretsInKeys() {
        val scope = DataScope("profile-id", "http://host:9119", "hermes-profile", 0L)
        val inMemory = scope.inMemoryKey("local")
        val persistent = scope.persistentKey("local")

        assertTrue(!inMemory.contains("token", ignoreCase = true))
        assertTrue(!inMemory.contains("password", ignoreCase = true))
        assertTrue(!inMemory.contains("secret", ignoreCase = true))
        assertTrue(!persistent.contains("token", ignoreCase = true))
        assertTrue(!persistent.contains("password", ignoreCase = true))
        assertTrue(!persistent.contains("secret", ignoreCase = true))
    }
}
