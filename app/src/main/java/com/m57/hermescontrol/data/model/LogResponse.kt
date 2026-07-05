package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class LogResponse(
    val lines: List<String>? = null,
    val logs: List<String>? = null,
)
