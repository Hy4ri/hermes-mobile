package com.m57.hermescontrol.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
private data class PasswordLoginPayload(
    val provider: String,
    val username: String,
    val password: String,
    val next: String,
)

/** JSON serialization for authentication requests and responses. */
object AuthPayloads {
    fun passwordLogin(
        username: String,
        password: String,
    ): String =
        OkHttpProvider.json.encodeToString(
            PasswordLoginPayload(
                provider = "basic",
                username = username,
                password = password,
                next = "",
            ),
        )
}
