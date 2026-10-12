package com.m57.hermescontrol.ui.authlogin

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One interactive sign-in option advertised by the dashboard
 * (`GET /api/auth/providers`). Password providers use the in-app form;
 * everything else (OIDC, Nous Portal, ...) signs in through the system browser.
 */
data class AuthProviderOption(
    val name: String,
    val displayName: String,
    val supportsPassword: Boolean,
)

/** Non-interactive service credential provider; never a sign-in option. */
private const val DRAIN_PROVIDER = "drain-secret"

/**
 * Parse the `GET /api/auth/providers` body. Returns null when the body is not
 * the expected shape so the caller can fall back to the status provider names.
 */
internal fun parseAuthProviders(json: String): List<AuthProviderOption>? {
    val array =
        runCatching {
            OkHttpProvider.json
                .parseToJsonElement(json)
                .jsonObject["providers"]
                ?.jsonArray
        }.getOrNull() ?: return null
    return array.mapNotNull(::providerOrNull)
}

private fun providerOrNull(element: kotlinx.serialization.json.JsonElement): AuthProviderOption? {
    val obj = (element as? JsonObject) ?: return null
    val name =
        obj["name"]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() } ?: return null
    if (name == DRAIN_PROVIDER) return null
    val display =
        obj["display_name"]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }
    val password = obj["supports_password"]?.jsonPrimitive?.booleanOrNull ?: false
    return AuthProviderOption(name = name, displayName = display ?: name, supportsPassword = password)
}

/**
 * Fallback for gateways whose `/api/auth/providers` is unavailable: build the
 * options from the bare names in `/api/status`. Only `basic` is known to take a
 * password; anything else is assumed to need the browser.
 */
internal fun providersFromStatusNames(names: List<String>): List<AuthProviderOption> =
    names
        .filter { it != DRAIN_PROVIDER }
        .map { AuthProviderOption(name = it, displayName = it, supportsPassword = it == "basic") }

/** Names of the auth flows the gateway advertises in `/api/status` (`auth_flows`). */
internal fun parseAuthFlows(array: JsonArray?): Set<String> =
    array?.mapNotNull { it.jsonPrimitive.content }?.toSet().orEmpty()
