package com.m57.hermescontrol.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.data.model.PathSuggestion
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.delay

/**
 * Composer `@`-path autocomplete for issue #536.
 *
 * Mirrors the desktop composer (`apps/desktop/.../use-context-suggestions.ts`):
 * when the user types an `@`-tagged token — `@`, `@file:`, `@file:src/`,
 * `@folder:`, or a bare `@name` fuzzy match — we fire the gateway's
 * `complete.path` RPC with the current token as `word` and surface a dropdown
 * of [PathSuggestion]s. Selecting one replaces the token in the composer with
 * the completion `text` (e.g. `@file:src/main.kt`).
 *
 * Mobile has no local `cwd`; we send only `session_id` + `word` and let the
 * gateway resolve the working directory from its session record.
 *
 * [PATH_TOKEN_RE] matches a trailing `@`-token being typed (the context
 * completion trigger).
 */

private val PATH_TOKEN_RE = Regex("""(?<!\S)@[\w./:\-~]*$""")

/**
 * Find the `@`-tagged token at the end of [text], or `null` if the caret is
 * not inside one. A token is "active" from the `@` up to the first whitespace.
 */
fun findPathToken(text: String): String? {
    val m = PATH_TOKEN_RE.find(text) ?: return null
    return m.value
}

/**
 * Replace the active trailing `@`-token in [text] with [replacement].
 * If no token is present, returns [text] unchanged. Pure helper so the
 * insertion logic is unit-testable without composition.
 */
fun replaceActivePathToken(
    text: String,
    replacement: String,
): String {
    val match = PATH_TOKEN_RE.find(text) ?: return text
    return text.replaceRange(match.range.first, match.range.last + 1, replacement)
}

/**
 * Call the gateway's `complete.path` RPC and return parsed suggestions.
 *
 * Suspends for the RPC result (delegated to [HermesWsClient.request], which
 * carries its own 120s timeout + disconnect rejection). Returns an empty list
 * on any failure (RPC error, timeout, parse error) so the UI simply hides.
 */
suspend fun fetchPathSuggestions(
    sessionId: String,
    word: String,
): List<PathSuggestion> {
    return try {
        val result =
            HermesWsClient
                .request(
                    method = WsMethods.COMPLETE_PATH,
                    params =
                        mapOf(
                            "session_id" to sessionId,
                            "word" to word,
                        ),
                ).await()
        val items =
            (result as? Map<*, *>)
                ?.get("items")
                ?.let { it as? List<*> }
                .orEmpty()
        items.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            PathSuggestion.fromMap(map)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Drives path-completion state from the composer [TextFieldValue] and exposes
 * a [select] callback that replaces the active `@`-token with the chosen text.
 *
 * Debounces (250ms) so we don't spam `complete.path` on every keystroke, and
 * ignores results that arrive after the token has changed (race guard),
 * matching desktop's `stillCurrent()` guard.
 */
@Composable
fun rememberPathCompletions(
    input: TextFieldValue,
    sessionId: String?,
    onTokenInserted: (TextFieldValue) -> Unit,
): PathCompletionState {
    var suggestions by remember { mutableStateOf<List<PathSuggestion>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val token by remember {
        derivedStateOf { if (sessionId != null) findPathToken(input.text) else null }
    }

    LaunchedEffect(input.text, sessionId) {
        if (token == null || sessionId == null) {
            suggestions = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        delay(250L)
        val tokenText: String = token ?: return@LaunchedEffect
        val fetched = fetchPathSuggestions(sessionId, tokenText)
        // Race guard: only commit if the token hasn't changed meanwhile.
        if (findPathToken(input.text) == tokenText) {
            suggestions = fetched
        }
        isLoading = false
    }

    val select: (PathSuggestion) -> Unit = { suggestion ->
        val replaced = replaceActivePathToken(input.text, suggestion.text)
        val newSelection = replaced.length
        onTokenInserted(
            input.copy(
                text = replaced,
                selection = androidx.compose.ui.text.TextRange(newSelection),
            ),
        )
        suggestions = emptyList()
        isLoading = false
    }

    return PathCompletionState(
        suggestions = suggestions,
        isLoading = isLoading,
        token = token,
        select = select,
    )
}

/** Immutable snapshot exposed by [rememberPathCompletions]. */
data class PathCompletionState(
    val suggestions: List<PathSuggestion>,
    val isLoading: Boolean,
    val token: String?,
    val select: (PathSuggestion) -> Unit,
)

/**
 * Dropdown listing [PathCompletionState.suggestions]. Renders nothing when
 * there are no suggestions. Mirrors the slash-command suggestion styling.
 */
@Composable
fun PathCompletionDropdown(state: PathCompletionState) {
    AnimatedVisibility(
        visible = state.suggestions.isNotEmpty(),
        enter = fadeIn(),
        exit = shrinkVertically(),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border =
                androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                ),
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(state.suggestions, key = { it.text }) { suggestion ->
                    val isDir = suggestion.isDirectory
                    DropdownMenuItem(
                        text = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = suggestion.display,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                suggestion.meta?.let { meta ->
                                    Text(
                                        text = "  $meta",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector =
                                    if (isDir) {
                                        Icons.Filled.Folder
                                    } else {
                                        Icons.AutoMirrored.Filled.InsertDriveFile
                                    },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { state.select(suggestion) },
                    )
                }
            }
        }
    }
}
