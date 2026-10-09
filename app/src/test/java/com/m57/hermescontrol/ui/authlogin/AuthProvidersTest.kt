package com.m57.hermescontrol.ui.authlogin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthProvidersTest {
    @Test
    fun `the OIDC-only response from issue 1515 parses as a non-password provider`() {
        val json =
            """{"providers":[{"name":"self-hosted","display_name":"Self-Hosted OIDC","supports_password":false}]}"""
        assertEquals(
            listOf(AuthProviderOption("self-hosted", "Self-Hosted OIDC", supportsPassword = false)),
            parseAuthProviders(json),
        )
    }

    @Test
    fun `all bundled interactive providers parse and the drain service credential is never offered`() {
        val json =
            """{"providers":[
                {"name":"basic","display_name":"Username & Password","supports_password":true},
                {"name":"nous","display_name":"Nous Research","supports_password":false},
                {"name":"drain-secret","display_name":"Drain Control (service credential)","supports_password":false}
            ]}"""
        assertEquals(listOf("basic", "nous"), parseAuthProviders(json)?.map { it.name })
        assertEquals(true, parseAuthProviders(json)?.first()?.supportsPassword)
    }

    @Test
    fun `missing display name falls back to the provider name and missing flag means no password`() {
        val parsed = parseAuthProviders("""{"providers":[{"name":"keycloak"}]}""")
        assertEquals(listOf(AuthProviderOption("keycloak", "keycloak", supportsPassword = false)), parsed)
    }

    @Test
    fun `malformed bodies return null so the status names are used instead`() {
        assertNull(parseAuthProviders("not json"))
        assertNull(parseAuthProviders("""{"detail":"no auth providers registered"}"""))
        assertEquals(emptyList<AuthProviderOption>(), parseAuthProviders("""{"providers":[{"display_name":"x"},7]}"""))
    }

    @Test
    fun `status name fallback treats only basic as a password provider`() {
        assertEquals(
            listOf(
                AuthProviderOption("basic", "basic", supportsPassword = true),
                AuthProviderOption("self-hosted", "self-hosted", supportsPassword = false),
            ),
            providersFromStatusNames(listOf("basic", "drain-secret", "self-hosted")),
        )
    }
}
