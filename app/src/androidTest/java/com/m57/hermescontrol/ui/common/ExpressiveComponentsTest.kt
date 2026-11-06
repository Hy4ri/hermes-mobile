package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExpressiveComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nativeButtonVariantsKeepClicksDisabledStateAndTargets() {
        val clicks = mutableListOf<String>()
        compose.setContent {
            HermesControlTheme {
                Column {
                    ExpressiveButton(onClick = { clicks += "filled" }, modifier = Modifier.testTag("filled")) {
                        Text("Save")
                    }
                    ExpressiveFilledTonalButton(onClick = { clicks += "tonal" }, modifier = Modifier.testTag("tonal")) {
                        Text("Retry")
                    }
                    ExpressiveOutlinedButton(
                        onClick = { clicks += "outlined" },
                        modifier = Modifier.testTag("outlined"),
                    ) {
                        Text("Cancel")
                    }
                    ExpressiveTextButton(onClick = { clicks += "text" }, modifier = Modifier.testTag("text")) {
                        Text("Confirm")
                    }
                    ExpressiveButton(
                        onClick = { clicks += "disabled" },
                        enabled = false,
                        modifier = Modifier.testTag("disabled"),
                    ) {
                        Text("Unavailable")
                    }
                    HermesIconButton(
                        Icons.Filled.Refresh,
                        "Refresh",
                        { clicks += "icon" },
                        modifier = Modifier.testTag("icon"),
                    )
                    HermesIconButton(
                        Icons.Filled.Refresh,
                        "Unavailable refresh",
                        { clicks += "disabled_icon" },
                        modifier = Modifier.testTag("disabled_icon"),
                        enabled = false,
                    )
                }
            }
        }
        listOf("filled", "tonal", "outlined", "text", "icon").forEach { tag ->
            compose
                .onNodeWithTag(tag)
                .assertIsEnabled()
                .assertTouchHeightIsEqualTo(48.dp)
                .apply {
                    if (tag == "icon") assertTouchWidthIsEqualTo(48.dp) else assertWidthIsAtLeast(48.dp)
                }.performClick()
        }
        listOf("disabled", "disabled_icon").forEach { tag ->
            compose.onNodeWithTag(tag).assertIsNotEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("filled", "tonal", "outlined", "text", "icon"), clicks) }
    }

    @Test
    fun wavyAndMorphingIndicatorsPreserveRealProgressSemantics() {
        var progress by mutableFloatStateOf(0.25f)
        compose.setContent {
            HermesControlTheme {
                Column {
                    ExpressiveCircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.testTag("circular"),
                    )
                    ExpressiveLinearProgressIndicator(progress = { progress }, modifier = Modifier.testTag("linear"))
                    ExpressiveCircularProgressIndicator(modifier = Modifier.testTag("busy_circular"))
                    ExpressiveLinearProgressIndicator(modifier = Modifier.testTag("busy_linear"))
                    Box(Modifier.height(80.dp)) { LoadingState() }
                }
            }
        }

        fun assertProgress(
            expected: ProgressBarRangeInfo,
            vararg tags: String,
        ) {
            tags.forEach { tag ->
                val progress = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, expected)
                // Native indeterminate circular progress deliberately owns a child semantics node.
                compose.onNodeWithTag(tag).assert(progress or hasAnyDescendant(progress))
            }
        }
        assertProgress(ProgressBarRangeInfo(0.25f, 0f..1f), "circular", "linear")
        assertProgress(ProgressBarRangeInfo.Indeterminate, "busy_circular", "busy_linear", "loading_spinner")
        compose.runOnIdle { progress = 0.75f }
        assertProgress(ProgressBarRangeInfo(0.75f, 0f..1f), "circular", "linear")
        compose.runOnIdle { progress = 2f }
        assertProgress(ProgressBarRangeInfo(1f, 0f..1f), "circular", "linear")
    }

    @Test
    fun checkedSwitchRetainsToggleRoleCallbackAndDisabledState() {
        var checked by mutableStateOf(false)
        var calls = 0
        compose.setContent {
            HermesControlTheme {
                Column {
                    ExpressiveSwitch(checked, {
                        checked = it
                        calls++
                    }, Modifier.testTag("switch"))
                    ExpressiveSwitch(true, { calls++ }, Modifier.testTag("locked"), enabled = false)
                    ExpressiveSwitch(true, null, Modifier.testTag("row_owned"))
                }
            }
        }
        compose
            .onNodeWithTag("switch")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertTouchHeightIsEqualTo(48.dp)
            .assertIsOff()
            .performClick()
            .assertIsOn()
        compose
            .onNodeWithTag("locked")
            .assertIsOn()
            .assertIsNotEnabled()
            .performClick()
        compose.onNodeWithTag("row_owned").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun connectedChoicesRemainSingleSelectedAndReachableInNarrowRtlLargeText() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.5f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                HermesControlTheme {
                    ExpressiveChoiceRow(
                        options = listOf("النظام الافتراضي", "المظهر الفاتح", "المظهر الداكن"),
                        selectedIndex = selected,
                        onSelect = { selected = it },
                        modifier = Modifier.width(280.dp),
                    )
                }
            }
        }
        compose
            .onNodeWithText("النظام الافتراضي")
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
            .assertIsSelected()
        compose
            .onNodeWithText("المظهر الداكن")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        compose.onNodeWithText("النظام الافتراضي").assertIsNotSelected()
        compose.runOnIdle { assertEquals(2, selected) }
    }

    @Test
    fun nativeDiscreteSliderKeepsItsRangeStepsAndCallback() {
        var value by mutableFloatStateOf(2f)
        compose.setContent {
            HermesControlTheme {
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.testTag("slider"),
                )
            }
        }
        compose
            .onNodeWithTag("slider")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(
                        2f,
                        0f..4f,
                        3,
                    ),
                ),
            ).performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(3f) }
        compose.runOnIdle { assertEquals(3f, value) }
    }
}
