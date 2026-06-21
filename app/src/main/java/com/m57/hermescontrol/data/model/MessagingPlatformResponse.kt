package com.m57.hermescontrol.data.model

data class MessagingPlatformResponse(
    val env_path: String,
    val gateway_start_command: String,
    val platforms: List<MessagingPlatform>,
)
