package com.m57.hermescontrol.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
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
import kotlin.math.abs

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

    @Test
    fun longDnGlyphPixelsSurviveTheButtonShapeClip() {
        val longDn = "CN=Example Multiline Root,O=Example Trust Services,L=Example City,C=ZZ"
        val item = certificates.first().copy(reference = certificates.first().reference.copy(subject = longDn))
        val reference = mutableStateOf(false)
        val textWidth = mutableStateOf(0.dp)
        val textStyle = mutableStateOf<TextStyle?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                HermesControlTheme(themePreference = theme) {
                    Surface(modifier = Modifier.width(280.dp)) {
                        if (reference.value) {
                            // #1518: do not propagate Surface's 280dp minimum to the reference text.
                            Box(
                                modifier = Modifier.padding(ButtonDefaults.TextButtonContentPadding),
                                propagateMinConstraints = false,
                            ) {
                                Text(
                                    longDn,
                                    modifier = Modifier.width(textWidth.value),
                                    style = checkNotNull(textStyle.value),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            AndroidCaCertificateList(listOf(item)) {}
                        }
                    }
                }
            }
        }
        val text = compose.onNodeWithText(longDn, useUnmergedTree = true)
        val actualBounds = text.getBoundsInRoot()
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val width = text.getBoundsInRoot().let { it.right - it.left }
        val actual = text.captureToImage().toPixelMap()
        compose.runOnIdle {
            textWidth.value = width
            // #1518: Button merges inherited paragraph defaults into labelLarge.
            textStyle.value = layouts.single().layoutInput.style
            reference.value = true
        }
        val referenceText = compose.onNodeWithText(longDn, useUnmergedTree = true)
        val referenceBounds = referenceText.getBoundsInRoot()
        val expected = referenceText.captureToImage().toPixelMap()
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val scheme = resolveColorScheme(ThemePreset.DEFAULT, theme == ThemePreference.DARK)
        var inkPixels = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (colorDistance(expected[x, y], scheme.primary) < 0.1f) {
                    inkPixels++
                    assertTrue(
                        "DN ink clipped at ($x, $y): actual=${actual[x, y]}, reference=${expected[x, y]}, " +
                            "actualBounds=$actualBounds, referenceBounds=$referenceBounds",
                        colorDistance(actual[x, y], scheme.surface) > 0.05f,
                    )
                }
            }
        }
        assertTrue("Reference must contain foreground ink", inkPixels > 0)
    }

    private fun colorDistance(
        a: Color,
        b: Color,
    ): Float = maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

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
                arrayOf<Any>(ThemePreference.LIGHT, 1.45f),
                arrayOf<Any>(ThemePreference.DARK, 1.45f),
                arrayOf<Any>(ThemePreference.LIGHT, 2f),
                arrayOf<Any>(ThemePreference.DARK, 2f),
            )
    }
}
