package com.m57.hermescontrol.ui.settings

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateBindingDraftTest {
    @Test
    fun `save requires a valid address port and selected certificate`() {
        val valid = CertificateBindingDraft("example.com", "443", "work")
        assertTrue(valid.canSave(null, emptyMap()))
        listOf(
            valid.copy(host = ""),
            valid.copy(host = "https://example.com"),
            valid.copy(port = "0"),
            valid.copy(port = "70000"),
            valid.copy(port = ""),
            valid.copy(alias = null),
        ).forEach { assertFalse(it.canSave(null, emptyMap())) }
        assertTrue(valid.copy(host = "[::1]", port = "65535").canSave(null, emptyMap()))
    }

    @Test
    fun `editing own canonical address is allowed but duplicate destination is not`() {
        val url = "https://example.com/".toHttpUrl()
        val bindings = mapOf(url.toString() to "work", "https://other.example/" to "device")
        val draft = CertificateBindingDraft("EXAMPLE.COM", "443", "work")
        assertTrue(draft.canSave(url, bindings))
        assertFalse(draft.canSave(null, bindings))
        assertFalse(draft.copy(host = "other.example").canSave(url, bindings))
    }

    @Test
    fun `unchanged cancelled selection is clean and reselection is an unsaved change`() {
        val initial = CertificateBindingDraft("example.com", "443", "work")
        assertFalse(initial.copy().changedFrom(initial))
        assertTrue(initial.copy(certificateReselected = true).changedFrom(initial))
        assertTrue(initial.copy(alias = "device").changedFrom(initial))
        assertTrue(initial.copy(host = "other.example").changedFrom(initial))
        assertTrue(initial.copy(port = "8443").changedFrom(initial))
        assertFalse(initial.copy(port = "8443").copy(port = "443").changedFrom(initial))
    }

    @Test
    fun `invalid port retains draft certificate and never enables save`() {
        val initial = CertificateBindingDraft("example.com", "443", "work")
        val invalid = initial.copy(port = "70000")
        assertTrue(invalid.validHost)
        assertFalse(invalid.validPort)
        assertFalse(invalid.canSave(null, emptyMap()))
        assertTrue(invalid.copy(port = "8443").canSave(null, emptyMap()))
    }
}
