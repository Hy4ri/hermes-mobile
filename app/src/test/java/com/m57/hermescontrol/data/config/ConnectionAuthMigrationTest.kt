package com.m57.hermescontrol.data.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionAuthMigrationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun fillsLegacyNullModesFromGlobalMode() {
        val state =
            ServerStoreState(
                wsAuthParam = "ticket",
                connectionProfiles =
                    listOf(
                        ConnectionProfile(id = "legacy", name = "Legacy"),
                    ),
            )

        val migrated = state.migrateConnectionAuthParam()

        assertEquals("ticket", migrated.connectionProfiles.single().wsAuthParam)
    }

    @Test
    fun preservesExplicitModes() {
        val state =
            ServerStoreState(
                wsAuthParam = "ticket",
                connectionProfiles =
                    listOf(
                        ConnectionProfile(id = "ticket", name = "Ticket", wsAuthParam = "ticket"),
                        ConnectionProfile(id = "token", name = "Token", wsAuthParam = "token"),
                        ConnectionProfile(id = "legacy", name = "Legacy"),
                    ),
            )

        val migrated = state.migrateConnectionAuthParam()

        assertEquals("ticket", migrated.connectionProfiles[0].wsAuthParam)
        assertEquals("token", migrated.connectionProfiles[1].wsAuthParam)
        assertEquals("ticket", migrated.connectionProfiles[2].wsAuthParam)
    }

    @Test
    fun migrationIsIdempotent() {
        val state =
            ServerStoreState(
                wsAuthParam = "token",
                connectionProfiles = listOf(ConnectionProfile(id = "legacy", name = "Legacy")),
            )

        val once = state.migrateConnectionAuthParam()

        assertEquals(once, once.migrateConnectionAuthParam())
    }

    @Test
    fun migrationSurvivesSerializationRoundTrip() {
        val state =
            ServerStoreState(
                wsAuthParam = "ticket",
                connectionProfiles = listOf(ConnectionProfile(id = "legacy", name = "Legacy")),
            ).migrateConnectionAuthParam()

        val restored = json.decodeFromString<ServerStoreState>(json.encodeToString(state))

        assertEquals(state, restored)
    }
}
