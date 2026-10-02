package com.m57.hermescontrol.data.theme.import

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ThemeColorUtils.parseLayer] — specifically the VS Code RGBA
 * contract (requirement #4): an 8-digit hex is `#rrggbbaa` with alpha as the
 * *last* two digits, matching desktop `normalizeHex`. The legacy ARGB reading
 * of the first two digits was a porting bug.
 *
 * Visual-value comparisons use [assertNear] (channel epsilon) because two
 * Colors that look identical but are built through different constructors can
 * differ in the packed representation.
 */
class ThemeColorUtilsTest {
    private val backdrop = Color(0xFF111111)

    private fun assertNear(
        expected: Color,
        actual: Color,
        eps: Float = 0.01f,
    ) {
        val expMsg = "expected r=${expected.red},g=${expected.green},b=${expected.blue}"
        val actMsg = "actual   r=${actual.red},g=${actual.green},b=${actual.blue}"
        assertTrue(
            "$expMsg\n$actMsg",
            kotlin.math.abs(expected.red - actual.red) < eps &&
                kotlin.math.abs(expected.green - actual.green) < eps &&
                kotlin.math.abs(expected.blue - actual.blue) < eps,
        )
    }

    @Test
    fun rgb6() {
        assertNear(Color(0xFF336699), ThemeColorUtils.parseLayer("#336699", backdrop)!!)
    }

    @Test
    fun rgb3_shorthand() {
        assertNear(Color(0xFF336699), ThemeColorUtils.parseLayer("#369", backdrop)!!)
    }

    @Test
    fun rgba8_alphaIsLastTwoDigits() {
        // VS Code is RGBA: alpha is the LAST two digits. #336699 at 0x80
        // (128/255) composites over the backdrop — the red channel must move,
        // and it must not equal a bare 0x80 red (the old ARGB misread).
        val blended = ThemeColorUtils.parseLayer("#33669980", backdrop)!!
        val a = 128f / 255f
        val expected =
            Color(
                red = 0x33 / 255f + (0x11 / 255f - 0x33 / 255f) * a,
                green = 0x66 / 255f + (0x11 / 255f - 0x66 / 255f) * a,
                blue = 0x99 / 255f + (0x11 / 255f - 0x99 / 255f) * a,
            )
        assertNear(expected, blended)
        assertTrue("3rd-8th digits are RGB, not a GUI alpha", blended.red < 0x80 / 255f)
    }

    @Test
    fun rgba8_fullyOpaqueLastDigitFF() {
        // #rrggbbff -> opaque, returns the pure RGB with no backdrop influence.
        assertNear(Color(0xFF336699), ThemeColorUtils.parseLayer("#336699ff", backdrop)!!)
    }

    @Test
    fun rgba4_shorthand_expandsSameAs8() {
        // #3698 -> #33669988
        val four = ThemeColorUtils.parseLayer("#3698", backdrop)!!
        val eight = ThemeColorUtils.parseLayer("#33669988", backdrop)!!
        assertNear(four, eight)
    }

    @Test
    fun alphaZeroFlattensToBackdrop() {
        // #33669900 -> fully transparent -> backdrop.
        assertNear(backdrop, ThemeColorUtils.parseLayer("#33669900", backdrop)!!)
    }

    @Test
    fun invalidReturnsNull() {
        assertNull(ThemeColorUtils.parseLayer("", backdrop))
        assertNull(ThemeColorUtils.parseLayer("#", backdrop))
        assertNull(ThemeColorUtils.parseLayer("#12345", backdrop))
        assertNull(ThemeColorUtils.parseLayer("#zzz", backdrop))
        assertNull(ThemeColorUtils.parseLayer(null, backdrop))
    }

    /** Regression: a real VS Code theme value with trailing alpha must parse. */
    @Test
    fun realWorldVscodeRgba() {
        // e.g. One Dark Pro stores surfaces with a trailing alpha byte.
        assertNear(Color(0xFF21252B), ThemeColorUtils.parseLayer("#21252BFF", backdrop)!!)
    }
}
