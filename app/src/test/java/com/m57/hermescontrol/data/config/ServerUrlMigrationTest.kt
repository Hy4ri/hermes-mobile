package com.m57.hermescontrol.data.config

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlMigrationTest {
    @Test
    fun `shouldMigrate true when baseUrl blank`() =
        runTest {
            val state =
                ServerStoreState(
                    host = "192.168.1.5",
                    port = 9119,
                    baseUrl = null,
                )
            val migration = ServerUrlMigration()
            assertTrue(migration.shouldMigrate(state))
        }

    @Test
    fun `shouldMigrate true when a profile baseUrl blank`() =
        runTest {
            val state =
                ServerStoreState(
                    baseUrl = "http://127.0.0.1:9119/",
                    connectionProfiles =
                        listOf(
                            ConnectionProfile(name = "Legacy", host = "10.0.0.1", port = 9220),
                        ),
                )
            val migration = ServerUrlMigration()
            assertTrue(migration.shouldMigrate(state))
        }

    @Test
    fun `shouldMigrate false when all baseUrls present`() =
        runTest {
            val state =
                ServerStoreState(
                    baseUrl = "http://127.0.0.1:9119/",
                    connectionProfiles =
                        listOf(
                            ConnectionProfile(name = "Modern", baseUrl = "https://example.com/hermes/"),
                        ),
                )
            val migration = ServerUrlMigration()
            assertFalse(migration.shouldMigrate(state))
        }

    @Test
    fun `migrate converts legacy host and port to baseUrl`() {
        val state =
            ServerStoreState(
                host = "192.168.1.5",
                port = 9119,
                baseUrl = null,
            )
        val result = ServerUrlMigration.migrateState(state)
        assertEquals("http://192.168.1.5:9119/", result.baseUrl)
    }

    @Test
    fun `migrate preserves already migrated baseUrl`() {
        val state =
            ServerStoreState(
                host = "192.168.1.5",
                port = 9119,
                baseUrl = "https://example.com/hermes/",
            )
        val result = ServerUrlMigration.migrateState(state)
        assertEquals("https://example.com/hermes/", result.baseUrl)
    }

    @Test
    fun `migrate converts legacy profiles`() {
        val state =
            ServerStoreState(
                connectionProfiles =
                    listOf(
                        ConnectionProfile(name = "Legacy", host = "10.0.0.1", port = 9220),
                    ),
            )
        val result = ServerUrlMigration.migrateState(state)
        assertEquals("http://10.0.0.1:9220/", result.connectionProfiles.first().baseUrl)
    }
}
