package com.m57.hermescontrol.data.config

import androidx.datastore.core.DataMigration
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint

/** Migrates the former host-and-port model to canonical complete base URLs. */
class ServerUrlMigration : DataMigration<ServerStoreState> {
    override suspend fun shouldMigrate(currentData: ServerStoreState): Boolean =
        currentData.baseUrl.isNullOrBlank() ||
            currentData.connectionProfiles.any { it.baseUrl.isNullOrBlank() }

    override suspend fun migrate(currentData: ServerStoreState): ServerStoreState = migrateState(currentData)

    override suspend fun cleanUp() = Unit

    companion object {
        internal fun migrateState(state: ServerStoreState): ServerStoreState =
            state.copy(
                baseUrl =
                    normalizeExisting(state.baseUrl)
                        ?: ServerEndpoint.fromLegacy(
                            state.host,
                            state.port,
                        ).baseUrl.toString(),
                connectionProfiles =
                    state.connectionProfiles.map { profile ->
                        profile.copy(
                            baseUrl =
                                normalizeExisting(profile.baseUrl)
                                    ?: ServerEndpoint.fromLegacy(
                                        profile.host,
                                        profile.port,
                                    ).baseUrl.toString(),
                        )
                    },
            )

        private fun normalizeExisting(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return ServerEndpoint
                .parse(
                    raw,
                    CleartextPolicy.ALLOW_WITH_WARNING,
                ).baseUrl
                .toString()
        }
    }
}
