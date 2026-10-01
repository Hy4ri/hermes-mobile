package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data object EmptyParams

@Serializable
data class SubscriptionPreviewParams(
    @SerialName("subscription_type_id") val subscriptionTypeId: String? = null,
)

@Serializable
data class SubscriptionChangeParams(
    @SerialName("subscription_type_id") val subscriptionTypeId: String? = null,
    @SerialName("cancel") val cancel: Boolean? = null,
)

@Serializable
data class SubscriptionUpgradeParams(
    @SerialName("subscription_type_id") val subscriptionTypeId: String? = null,
)

@Serializable
data class VaultSourceSetParams(
    @SerialName("name") val name: String? = null,
    @SerialName("enabled") val enabled: Boolean? = null,
)

@Serializable
data class VaultUnlockParams(
    @SerialName("name") val name: String? = null,
    @SerialName("password") val password: String? = null,
)

@Serializable
data class VaultLockParams(
    @SerialName("name") val name: String? = null,
)
