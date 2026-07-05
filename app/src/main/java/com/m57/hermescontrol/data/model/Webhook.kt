package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class WebhooksResponse(
    val enabled: Boolean,
    val base_url: String?,
    val subscriptions: List<WebhookSubscription>?,
)

@Serializable
data class WebhookSubscription(
    val name: String,
    val description: String?,
    val events: List<String>?,
    val deliver: String?,
    val deliver_only: Boolean?,
    val prompt: String?,
    val skills: List<String>?,
    val created_at: String?,
    val url: String,
    val secret_set: Boolean?,
    val enabled: Boolean?,
)

@Serializable
data class CreateWebhookRequest(
    val name: String,
    val description: String? = null,
    val events: List<String>? = null,
    val prompt: String? = null,
    val skills: List<String>? = null,
    val deliver: String? = null,
    val deliver_only: Boolean? = null,
    val deliver_chat_id: String? = null,
    val secret: String? = null,
)

@Serializable
data class WebhooksToggleRequest(
    val enabled: Boolean,
)

@Serializable
data class WebhookToggleSubscriptionRequest(
    val enabled: Boolean,
)

@Serializable
data class DeleteWebhookResponse(
    val ok: Boolean,
)
