package com.m57.hermescontrol.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A single-line title [Text] that, when its content is wider than the available
 * space, performs a one-shot horizontal auto-scroll (marquee) so the user can
 * read the whole string.
 *
 * The scroll fires automatically once when the title first lays out and
 * overflows, and again every time the title is tapped. If the text fits within
 * the available width there is no animation — it simply renders statically.
 *
 * @param text The title string.
 * @param onClick Optional callback fired on tap in addition to re-triggering the
 *   scroll (e.g. focus the input, open details).
 */
@Composable
fun AutoScrollingTitleText(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    style: TextStyle = LocalTextStyle.current,
) {
    val scrollState = rememberScrollState()
    var layoutWidth by remember { mutableStateOf(0) }
    var textWidth by remember { mutableStateOf(0) }
    var hasLaidOut by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // True only when the text actually overflows its container.
    val overflows = layoutWidth > 0 && textWidth > layoutWidth

    fun triggerScroll() {
        if (!overflows) return
        val maxScroll = (textWidth - layoutWidth).toFloat()
        coroutineScope.launch {
            // Restart from the beginning so each trigger reads left-to-right.
            scrollState.scrollTo(0)
            delay(400)
            scrollState.animateScrollTo(maxScroll)
            delay(600)
            // Snap back so the start of the title is always visible at rest.
            scrollState.scrollTo(0)
        }
    }

    // Auto-scroll once when the title first lays out and overflows.
    LaunchedEffect(hasLaidOut, overflows) {
        if (hasLaidOut && overflows) {
            delay(500)
            triggerScroll()
        }
    }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier =
                Modifier
                    .horizontalScroll(scrollState, enabled = false)
                    .fillMaxWidth()
                    .clickable {
                        triggerScroll()
                        onClick?.invoke()
                    },
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            style = style,
            onTextLayout = { result: TextLayoutResult ->
                textWidth = result.size.width
                layoutWidth = result.layoutInput.constraints.maxWidth
                hasLaidOut = true
            },
        )
    }
}
