package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.theme.ThemeRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@MediumTest
class ThemeComponentGalleryTest(
    private val preset: ThemePreset,
    private val dark: Boolean,
) {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var samples = emptyList<Sample>()

    @Test
    fun enabledControlsRenderTheirAccessibleDefaultStateColors() {
        composeTestRule.setContent {
            HermesControlTheme(
                themePreset = preset,
                themePreference = if (dark) ThemePreference.DARK else ThemePreference.LIGHT,
            ) {
                Gallery()
            }
        }
        composeTestRule.waitForIdle()
        // Assert the public defaults from the installed Compose version, not a copied token table.
        assertTrue("gallery must publish its state contracts", samples.isNotEmpty())
        samples.forEach { sample ->
            val a = sample.foreground.luminance()
            val b = sample.background.luminance()
            val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
            assertTrue("$preset dark=$dark ${sample.tag}: $ratio < ${sample.minimum}", ratio >= sample.minimum)
        }
        // Pixel evidence catches defaults that differ from the actual rendered state (including focus).
        samples.groupBy { it.tag }.forEach { (tag, contracts) ->
            val pixels =
                composeTestRule
                    .onNodeWithTag(tag)
                    .performScrollTo()
                    .captureToImage()
                    .toPixelMap()
            val rendered =
                buildSet {
                    for (y in 0 until pixels.height) {
                        for (x in 0 until pixels.width) add(pixels[x, y].toArgb())
                    }
                }
            contracts.forEach { sample ->
                assertTrue("$preset dark=$dark $tag must render its foreground", sample.foreground.toArgb() in rendered)
                if (sample.checkBackground) {
                    assertTrue("$preset dark=$dark $tag must render its fill", sample.background.toArgb() in rendered)
                }
            }
        }
    }

    @Composable
    private fun Gallery() {
        val c = MaterialTheme.colorScheme
        val switches = SwitchDefaults.colors()
        val radios = RadioButtonDefaults.colors()
        val checks = CheckboxDefaults.colors()
        val sliders = SliderDefaults.colors()
        val fields = OutlinedTextFieldDefaults.colors()
        val segments = SegmentedButtonDefaults.colors()
        val focus = remember { FocusRequester() }
        SideEffect {
            samples =
                listOf(
                    Sample("switch_on", switches.checkedThumbColor, switches.checkedTrackColor, checkBackground = true),
                    Sample("switch_on", switches.checkedTrackColor, c.surface),
                    Sample(
                        "switch_off",
                        switches.uncheckedThumbColor,
                        switches.uncheckedTrackColor,
                        checkBackground = true,
                    ),
                    Sample("switch_off", switches.uncheckedBorderColor, c.surface),
                    Sample("radio_on", radios.selectedColor, c.surface),
                    Sample("radio_off", radios.unselectedColor, c.surface),
                    Sample("check_on", checks.checkedCheckmarkColor, checks.checkedBoxColor, checkBackground = true),
                    Sample("check_on", checks.checkedBorderColor, c.surface),
                    Sample("check_off", checks.uncheckedBorderColor, c.surface),
                    Sample("slider", sliders.activeTrackColor, sliders.inactiveTrackColor, checkBackground = true),
                    Sample(
                        "field_normal",
                        fields.unfocusedIndicatorColor,
                        fields.unfocusedContainerColor.compositeOver(c.surface),
                    ),
                    Sample("field_normal", fields.unfocusedLabelColor, c.surface, 4.5f),
                    Sample(
                        "field_focus",
                        fields.focusedIndicatorColor,
                        fields.focusedContainerColor.compositeOver(c.surface),
                    ),
                    Sample("field_focus", fields.focusedLabelColor, c.surface, 4.5f),
                    Sample(
                        "field_error",
                        fields.errorIndicatorColor,
                        fields.errorContainerColor.compositeOver(c.surface),
                    ),
                    Sample("field_error", fields.errorSupportingTextColor, c.surface, 4.5f),
                    Sample("segment_on", segments.activeContentColor, segments.activeContainerColor, 4.5f, true),
                    Sample("segment_on", segments.activeBorderColor, segments.activeContainerColor),
                    Sample(
                        "segment_off",
                        segments.inactiveContentColor,
                        segments.inactiveContainerColor.compositeOver(c.surface),
                        4.5f,
                    ),
                    Sample("segment_off", segments.inactiveBorderColor, c.surface),
                    Sample("primary_text", c.primary, c.surface, 4.5f),
                    Sample("error_text", c.error, c.surface, 4.5f),
                )
        }
        Surface(color = c.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) {
                Text("$preset ${if (dark) "dark" else "light"}")
                Row {
                    Switch(true, {}, Modifier.testTag("switch_on"))
                    Switch(false, {}, Modifier.testTag("switch_off"))
                    RadioButton(true, {}, Modifier.testTag("radio_on"))
                    RadioButton(false, {}, Modifier.testTag("radio_off"))
                }
                Row {
                    Checkbox(true, {}, Modifier.testTag("check_on"))
                    Checkbox(false, {}, Modifier.testTag("check_off"))
                }
                Slider(0.5f, {}, Modifier.testTag("slider"))
                OutlinedTextField("Value", {
                }, Modifier.fillMaxWidth().testTag("field_normal"), readOnly = true, label = { Text("Normal") })
                OutlinedTextField(
                    "Value",
                    {
                    },
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(
                            focus,
                        ).testTag("field_focus"),
                    readOnly = true,
                    label = { Text("Focused") },
                )
                OutlinedTextField("Value", {
                }, Modifier.fillMaxWidth().testTag("field_error"), readOnly = true, isError = true, label = {
                    Text("Error")
                }, supportingText = { Text("Error text") })
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        true,
                        {},
                        SegmentedButtonDefaults.itemShape(0, 2),
                        Modifier.testTag("segment_on"),
                    ) { Text("Selected") }
                    SegmentedButton(
                        false,
                        {
                        },
                        SegmentedButtonDefaults.itemShape(
                            1,
                            2,
                        ),
                        Modifier.testTag("segment_off"),
                    ) { Text("Unselected") }
                }
                Text("Primary foreground", Modifier.testTag("primary_text"), color = c.primary)
                Text("Error foreground", Modifier.testTag("error_text"), color = c.error)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    private data class Sample(
        val tag: String,
        val foreground: Color,
        val background: Color,
        val minimum: Float = 3f,
        val checkBackground: Boolean = false,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} dark={1}")
        fun presets(): List<Array<Any>> =
            ThemeRegistry.flatMap { (preset, _) -> listOf(true, false).map { arrayOf<Any>(preset, it) } }
    }
}
