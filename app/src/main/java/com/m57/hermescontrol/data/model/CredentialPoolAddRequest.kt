package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/** Body for POST api/credentials/pool. `apiKey` serializes as `api_key`. */
@Serializable
data class CredentialPoolAddRequest(
    val provider: String,
    val apiKey: String,
    val label: String? = null,
)
