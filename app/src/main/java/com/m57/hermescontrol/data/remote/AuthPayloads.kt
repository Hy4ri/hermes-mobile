package com.m57.hermescontrol.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
private data class PasswordLoginPayload(
    val provider: String,
    val username: String,
    val password: String,
    val next: String,
)

@Serializable
private data class WebSocketTicketPayload(
    val ticket: String,
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

    fun webSocketTicket(body: String): String? =
        runCatching {
            OkHttpProvider.json.decodeFromString<WebSocketTicketPayload>(body)
                .ticket
                .takeIf { it.isNotBlank() }
        }.getOrNull()
}
