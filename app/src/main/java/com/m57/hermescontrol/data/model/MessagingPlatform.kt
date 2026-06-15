package com.m57.hermescontrol.data.model

data class MessagingPlatform(
    val name: String,
    val enabled: Boolean,
    val configured: Boolean,
    val details: Map<String, String>?,
)
