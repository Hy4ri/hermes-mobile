package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Models for the Credential Vault RPC surface (issue #1090).
 *
 * Backend: `tui_gateway/methods_vault.py`.
 * Sources represent local encrypted vault or external managers (1Password, Bitwarden).
 */

@Serializable
data class VaultSource(
    val name: String,
    @SerialName("display_name") val displayName: String = name,
    val enabled: Boolean = false,
    @SerialName("needs_unlock") val needsUnlock: Boolean = false,
    val unlocked: Boolean = false,
    val installed: Boolean = false,
)

@Serializable
data class VaultSourcesResponse(
    val sources: List<VaultSource> = emptyList(),
)

@Serializable
data class VaultItem(
    val id: String,
    val backend: String = "local",
    val kind: String = "login",
    val label: String = "",
    val origin: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("identifier_type") val identifierType: String? = null,
    val identifier: String? = null,
    @SerialName("has_otp") val hasOtp: Boolean = false,
)

@Serializable
data class VaultListResponse(
    val items: List<VaultItem> = emptyList(),
)

@Serializable
data class VaultSourceSetResponse(
    val name: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class VaultUnlockResponse(
    val name: String? = null,
    val unlocked: Boolean? = null,
)

@Serializable
data class VaultLockResponse(
    val locked: Boolean? = null,
)
