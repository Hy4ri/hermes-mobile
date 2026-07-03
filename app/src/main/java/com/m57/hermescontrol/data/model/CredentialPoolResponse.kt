package com.m57.hermescontrol.data.model

data class CredentialPoolResponse(
    val providers: List<CredentialPoolProvider>? = null,
)

data class CredentialPoolProvider(
    val provider: String? = null,
    val entries: List<CredentialPoolEntry>? = null,
)

data class CredentialPoolEntry(
    val index: Int? = null,
    val label: String? = null,
    val token_preview: String? = null,
    val auth_type: String? = null,
    val last_status: String? = null,
)
