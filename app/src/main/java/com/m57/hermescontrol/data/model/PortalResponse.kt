package com.m57.hermescontrol.data.model
import kotlinx.serialization.Serializable

@Serializable
data class PortalResponse(
    val logged_in: Boolean? = null,
    val portal_url: String? = null,
    val inference_url: String? = null,
    val provider: String? = null,
    val subscription_url: String? = null,
    val features: List<PortalFeature>? = null,
)

@Serializable
data class PortalFeature(
    val label: String? = null,
    val state: String? = null,
)
