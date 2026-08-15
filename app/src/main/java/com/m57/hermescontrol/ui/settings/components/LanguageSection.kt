package com.m57.hermescontrol.ui.settings.components

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.settings.SectionCard
import com.m57.hermescontrol.util.LocaleContextWrapper

/**
 * Supported languages as (code, display label) pairs. Add a new language
 * here (plus its label in strings.xml) and both the picker and the summary
 * adapt automatically.
 */
@Composable
internal fun supportedLanguages(): List<Pair<String, String>> =
    listOf(
        LocaleContextWrapper.SYSTEM_LANGUAGE to stringResource(R.string.language_system),
        "en" to stringResource(R.string.language_english),
        "zh" to stringResource(R.string.language_chinese),
        "ko" to stringResource(R.string.language_korean),
    )

/** Display label for a language code, falling back to English for unknown codes. */
@Composable
internal fun languageLabel(code: String): String =
    supportedLanguages().firstOrNull { it.first == code }?.second
        ?: stringResource(R.string.language_english)

@Composable
internal fun LanguageSection(
    appLanguage: String,
    onAppLanguageChange: (String) -> Unit,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.settings_item_language),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val languageOptions = supportedLanguages()
        val activity = LocalActivity.current
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(languageLabel(appLanguage))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                languageOptions.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            if (code != appLanguage) {
                                onAppLanguageChange(code)
                                // MainActivity is a plain ComponentActivity
                                // (not AppCompatActivity), so the locale only
                                // takes effect after the activity is recreated.
                                activity?.recreate()
                            }
                        },
                    )
                }
            }
        }
    }
}
