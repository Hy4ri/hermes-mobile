package com.m57.hermescontrol.data.model

data class DebugShareResponse(
    val ok: Boolean? = null,
    val urls: Map<String, String>? = null,
    val failures: List<String>? = null,
    val redacted: Boolean? = null,
    val auto_delete_seconds: Long? = null,
)
