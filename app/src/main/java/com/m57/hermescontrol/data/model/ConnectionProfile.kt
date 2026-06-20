package com.m57.hermescontrol.data.model

import java.util.UUID

data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
)
