package com.m57.hermescontrol.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.remote.AndroidCaCertificate
import com.m57.hermescontrol.data.remote.CaReference
import com.m57.hermescontrol.data.remote.CaSource
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.theme.resolveColorScheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.security.cert.X509Certificate

/** Exercises the production DN list using synthetic certificates; never changes the CA store. */
@RunWith(Parameterized::class)
class AndroidCaPickerRowTest(
    private val theme: ThemePreference,
    private val fontScale: Float,
) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val subject = "CN=Example Root,O=Example Organization,C=ZZ"
    private val certificates =
        (0..1).map { index ->
            AndroidCaCertificate(
                CaReference("system:$index", CaSource.SYSTEM, index.toString().repeat(64), subject),
                mockk<X509Certificate>(),
            )
        }
    private var clicked: AndroidCaCertificate? = null

    @Test
    fun fullDnButtonsPreserveMultilineTextColorAndExactIdentity() {
        show(certificates)
        val buttons = compose.onAllNodesWithText(subject)
        buttons.assertCountEquals(2)
        buttons[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            ).assertCountEquals(0)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(subject, useUnmergedTree = true)[0].performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { it(layouts) }
        val text = layouts.single()
        assertEquals(subject, text.layoutInput.text.text)
        assertTrue(text.lineCount > 1)
        assertTrue((0 until text.lineCount).none { text.isLineEllipsized(it) })
        assertEquals(
            resolveColorScheme(ThemePreset.DEFAULT, theme == ThemePreference.DARK).primary,
            text.layoutInput.style.color,
        )
        buttons[1].performClick()
        compose.runOnIdle { assertSame(certificates[1], clicked) }
    }

    @Test
    fun onlyAdjacentEntriesHaveThinTextAlignedSeparators() {
        show(certificates)
        val separators = compose.onAllNodesWithTag("ca-certificate-divider", useUnmergedTree = true)
        separators.assertCountEquals(1)
        val line = separators[0].getBoundsInRoot()
        val text = compose.onAllNodesWithText(subject, useUnmergedTree = true)[0].getBoundsInRoot()
        val button = compose.onAllNodesWithText(subject)[0].getBoundsInRoot()
        assertEquals(text.left.value, line.left.value, 0.1f)
        assertEquals(text.right.value, line.right.value, 0.1f)
        assertTrue(line.left > button.left)
        assertTrue(line.bottom > line.top)
        assertEquals(0.5f, (line.bottom - line.top).value, 0.6f)
    }

    @Test
    fun singleEntryHasNoOuterBorders() {
        show(certificates.take(1))
        compose.onAllNodesWithTag("ca-certificate-divider", useUnmergedTree = true).assertCountEquals(0)
    }

    private fun show(items: List<AndroidCaCertificate>) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                HermesControlTheme(themePreference = theme) {
                    Surface(modifier = Modifier.width(280.dp)) {
                        AndroidCaCertificateList(items) { clicked = it }
                    }
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, font={1}")
        fun configurations() =
            listOf(
                arrayOf<Any>(ThemePreference.LIGHT, 1.25f),
                arrayOf<Any>(ThemePreference.DARK, 1.25f),
                arrayOf<Any>(ThemePreference.LIGHT, 2f),
                arrayOf<Any>(ThemePreference.DARK, 2f),
            )
    }
}
