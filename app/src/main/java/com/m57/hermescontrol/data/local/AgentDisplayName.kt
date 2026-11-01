package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Device-local display name for the agent, one per connection + server profile (#1747).
 *
 * The name is a label on this device only. It never renames the server profile. Resolution order:
 * custom name, then the server profile name, then [DEFAULT_NAME] for the default profile.
 */
object AgentDisplayName {
    const val DEFAULT_NAME = "Hermes"
    const val MAX_LENGTH = 40
    private const val FILE_NAME = "agent_display_names"

    private var prefs: SharedPreferences? = null
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Stored custom names, keyed by [key]. */
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    /** The agent name for the active connection and profile. */
    val current: StateFlow<String> =
        combine(AuthManager.dataScopeFlow, _names) { scope, names ->
            resolve(scope?.connectionProfileId, scope?.activeProfileId, names)
        }.stateIn(CoroutineScope(Dispatchers.Default), SharingStarted.Eagerly, DEFAULT_NAME)

    fun init(context: Context) {
        val p = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        prefs = p
        _names.value =
            p.all.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
    }

    /** Custom name for one profile, or null when none is set. */
    fun custom(
        connectionProfileId: String?,
        profileName: String?,
    ): String? = _names.value[key(connectionProfileId, profileName)]

    fun resolve(
        connectionProfileId: String?,
        profileName: String?,
        names: Map<String, String> = _names.value,
    ): String {
        names[key(connectionProfileId, profileName)]?.let { return it }
        val profile = profileName?.trim().orEmpty()
        return if (profile.isEmpty() || profile == AuthManager.DEFAULT_PROFILE_ID) DEFAULT_NAME else profile
    }

    /** Sets the custom name. A blank [name] removes it, so the profile name shows again. */
    fun set(
        connectionProfileId: String?,
        profileName: String?,
        name: String?,
    ) {
        val k = key(connectionProfileId, profileName)
        val clean = name?.trim()?.take(MAX_LENGTH).orEmpty()
        _names.value = if (clean.isEmpty()) _names.value - k else _names.value + (k to clean)
        prefs?.edit()?.apply { if (clean.isEmpty()) remove(k) else putString(k, clean) }?.apply()
    }

    /** Moves a custom name to the new profile name after a server-side rename. */
    fun rename(
        connectionProfileId: String?,
        oldProfileName: String,
        newProfileName: String,
    ) {
        val name = custom(connectionProfileId, oldProfileName) ?: return
        set(connectionProfileId, oldProfileName, null)
        set(connectionProfileId, newProfileName, name)
    }

    @VisibleForTesting
    internal fun key(
        connectionProfileId: String?,
        profileName: String?,
    ): String {
        val conn = connectionProfileId?.takeIf { it.isNotBlank() } ?: AuthManager.DEFAULT_PROFILE_ID
        val profile = profileName?.takeIf { it.isNotBlank() } ?: AuthManager.DEFAULT_PROFILE_ID
        // Length prefix keeps ids that contain the separator unambiguous.
        return "${conn.length}:$conn|$profile"
    }

    @VisibleForTesting
    internal fun resetForTest() {
        prefs = null
        _names.value = emptyMap()
    }
}
