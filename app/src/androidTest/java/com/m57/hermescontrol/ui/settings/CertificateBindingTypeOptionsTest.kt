package com.m57.hermescontrol.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.CaReference
import com.m57.hermescontrol.data.remote.CaSource
import com.m57.hermescontrol.data.remote.CertificateEditSession
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CertificateBindingTypeOptionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reselectingTlsKeepsCaAndSwitchingToMtlsClearsIt() = verifySelection(initialTls = true)

    @Test
    fun reselectingMtlsKeepsClientAliasAndSwitchingToTlsClearsIt() = verifySelection(initialTls = false)

    private fun verifySelection(initialTls: Boolean) {
        val ca = CaReference("system:test", CaSource.SYSTEM, "a".repeat(64), "CN=Example Root").encode()
        val clientAlias = "example-client"
        val tls = mutableStateOf(initialTls)
        val initial = CertificateBindingDraft("example.test", "8443", if (initialTls) ca else clientAlias, true)
        var draft = initial
        var error: Int? = R.string.mtls_save_error
        var changes = 0
        val session = CertificateEditSession()
        val token = session.beginSelection()
        compose.setContent {
            HermesControlTheme {
                CertificateBindingTypeOptions(tls.value, enabled = true) { value ->
                    changes++
                    tls.value = value
                    draft = draft.copy(alias = null)
                    session.invalidate()
                    error = null
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        fun typeLabel(value: Boolean) = context.getString(if (value) R.string.tls_type_tls else R.string.tls_type_mtls)

        compose
            .onNodeWithContentDescription(typeLabel(initialTls))
            .assertIsSelected()
            .performClick()
            .performClick()
        compose.runOnIdle {
            assertEquals(0, changes)
            assertSame(initial, draft)
            assertEquals(R.string.mtls_save_error, error)
            assertTrue("Same-type clicks must preserve the chooser token", session.accept(token))
        }
        val switchedToken = session.beginSelection()
        compose.onNodeWithContentDescription(typeLabel(!initialTls)).performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(1, changes)
            assertEquals(!initialTls, tls.value)
            assertNull(draft.alias)
            assertEquals(initial.host, draft.host)
            assertEquals(initial.port, draft.port)
            assertEquals(initial.certificateReselected, draft.certificateReselected)
            assertNull(error)
            assertFalse("A real switch must invalidate the chooser token", session.accept(switchedToken))
            draft = draft.copy(alias = if (initialTls) clientAlias else ca)
        }
        val chosen = draft
        compose.onNodeWithContentDescription(typeLabel(!initialTls)).performClick()
        compose.runOnIdle {
            assertEquals(1, changes)
            assertSame(chosen, draft)
        }
    }
}
