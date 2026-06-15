package com.m57.hermescontrol.data.model

data class WebhooksResponse(
    val enabled: Boolean,
    val base_url: String?,
    val subscriptions: List<WebhookSubscription>?,
)

data class WebhookSubscription(
    val name: String,
    val url: String,
    val events: List<String>?,
)

data class WebhooksToggleRequest(
    val enabled: Boolean,
)
