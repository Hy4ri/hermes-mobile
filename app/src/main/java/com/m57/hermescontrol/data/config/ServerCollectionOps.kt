package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.ServerEndpoint

fun ServerStoreState.addOrReplaceServer(profile: ConnectionProfile): ServerStoreState {
    val updated = connectionProfiles.filter { it.id != profile.id } + profile
    return copy(connectionProfiles = updated)
}

fun ServerStoreState.removeServer(id: String): ServerStoreState {
    val updated = connectionProfiles.filter { it.id != id }
    val newSelected = if (selectedProfileId == id) null else selectedProfileId
    return copy(connectionProfiles = updated, selectedProfileId = newSelected)
}

fun ServerStoreState.switchToServer(id: String?): ServerStoreState = copy(selectedProfileId = id)

fun ServerStoreState.selfHealed(): ServerStoreState {
    val hasActive = connectionProfiles.any { it.id == selectedProfileId }
    val newSelected = if (selectedProfileId != null && !hasActive) null else selectedProfileId

    val validItems = bottomNavItems.filter { it.isNotBlank() }
    val finalBottomNavItems =
        if (validItems.isEmpty()) {
            listOf("ChatScreen", "SkillsScreen", "CronJobsScreen", "SystemScreen", "SettingsScreen")
        } else {
            validItems
        }

    return copy(
        selectedProfileId = newSelected,
        bottomNavItems = finalBottomNavItems,
    )
}

val ServerStoreState.resolvedBaseUrl: String
    get() {
        val selected = connectionProfiles.firstOrNull { it.id == selectedProfileId }
        if (selected != null) return selected.resolvedBaseUrl
        return baseUrl
            ?.let {
                ServerEndpoint
                    .parse(
                        it,
                        CleartextPolicy.ALLOW_WITH_WARNING,
                    ).baseUrl
                    .toString()
            }
            ?: ServerEndpoint.fromLegacy(host, port).baseUrl.toString()
    }

val ServerStoreState.resolvedHost: String
    get() =
        ServerEndpoint
            .parse(
                resolvedBaseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            ).baseUrl
            .host

val ServerStoreState.resolvedPort: Int
    get() =
        ServerEndpoint
            .parse(
                resolvedBaseUrl,
                CleartextPolicy.ALLOW_WITH_WARNING,
            ).baseUrl
            .port
