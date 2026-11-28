package com.m57.hermescontrol.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.theme.ThemePreference

/**
 * Display-only card posted by a plugin. Used by the full-bleed transcript and [SystemBubble]
 * so `plugin_card` never falls through to an empty timeline chip.
 */
@Composable
internal fun PluginCardBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val card = message.pluginCard ?: return
    val attrs =
        card.directiveAttrs.entries.joinToString(separator = " · ") { (key, value) -> "$key=$value" }
    val description = stringResource(R.string.plugin_card_cd, card.pluginId)
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = description }
                .testTag("plugin_card"),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.plugin_card_from, card.pluginId),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("plugin_card_header"),
            )
            MarkdownText(
                text = message.content,
                textColor = MaterialTheme.colorScheme.onSurface,
            )
            if (card.directiveName != null || attrs.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    card.directiveName?.let { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (attrs.isNotEmpty()) {
                        Text(
                            text = attrs,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private val previewCard =
    ChatMessage(
        id = "plugin-card-weather-c1",
        role = MessageRole.SYSTEM,
        content = "Rain until 18:00. Bring a jacket.",
        pluginCard =
            PluginCardMeta(
                pluginId = "weather",
                cardId = "c1",
                directiveName = "forecast",
                directiveAttrs = mapOf("city" to "Salt", "units" to "c"),
            ),
    )

@Preview(name = "Light", widthDp = 360, heightDp = 180)
@Composable
private fun PluginCardLightPreview() {
    HermesControlTheme(themePreference = ThemePreference.LIGHT) {
        Surface { PluginCardBubble(previewCard) }
    }
}

@Preview(name = "Dark", widthDp = 360, heightDp = 180, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PluginCardDarkPreview() {
    HermesControlTheme(themePreference = ThemePreference.DARK) {
        Surface { PluginCardBubble(previewCard) }
    }
}

@Preview(name = "Narrow Arabic large text", widthDp = 320, heightDp = 220, fontScale = 1.5f, locale = "ar")
@Composable
private fun PluginCardArabicPreview() {
    HermesControlTheme(themePreference = ThemePreference.LIGHT) {
        Surface { PluginCardBubble(previewCard) }
    }
}
