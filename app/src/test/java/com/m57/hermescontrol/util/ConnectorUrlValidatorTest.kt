package com.m57.hermescontrol.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorUrlValidatorTest {
    @Test
    fun `valid https urls are accepted`() {
        assertTrue(ConnectorUrlValidator.isValidHttpsUrl("https://auth.hermes.nousresearch.com/oauth/google"))
        assertTrue(
            ConnectorUrlValidator.isValidHttpsUrl("https://github.com/login/oauth/authorize?client_id=123&scope=repo"),
        )
        assertTrue(ConnectorUrlValidator.isValidHttpsUrl("https://linear.app/oauth/authorize?response_type=code"))
        assertTrue(ConnectorUrlValidator.isValidHttpsUrl("https://slack.com/oauth/v2/authorize"))
        assertTrue(ConnectorUrlValidator.isValidHttpsUrl("https://example.com:8443/auth?foo=bar#section"))
        assertTrue(ConnectorUrlValidator.isValidHttpsUrl("HTTPS://EXAMPLE.COM/AUTH"))
    }

    @Test
    fun `null, empty, and whitespace strings are rejected`() {
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl(null))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl(""))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("   "))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("\t\n"))
    }

    @Test
    fun `non-https schemes are rejected`() {
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("http://example.com/auth"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("ftp://example.com/file"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("javascript:alert(1)"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("data:text/html,<html>"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("file:///etc/passwd"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("intent://example.com"))
    }

    @Test
    fun `urls with userinfo are rejected`() {
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://user:password@example.com/oauth"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://user@example.com/oauth"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://admin:@example.com/oauth"))
    }

    @Test
    fun `urls with backslashes are rejected`() {
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com\\evil.com"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com/path\\something"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https:\\\\example.com"))
    }

    @Test
    fun `urls with control characters or whitespace are rejected`() {
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com/auth\u0000"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com/auth\r\nSet-Cookie:bad"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com/auth test"))
    }

    @Test
    fun `urls with invalid host or port are rejected`() {
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https:///path"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com:0/auth"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com:65536/auth"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://example.com:99999/auth"))
        assertFalse(ConnectorUrlValidator.isValidHttpsUrl("https://invalid_host_chars!/auth"))
    }
}
