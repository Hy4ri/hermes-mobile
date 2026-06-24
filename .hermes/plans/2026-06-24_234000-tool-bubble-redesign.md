# Tool Bubble Redesign Implementation Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Redesign the `ToolBubble` component to show a glanceable summary line by default (tool name + one-line arg summary), a clean structured expanded view (not raw JSON), and raw JSON as a toggle. Add text selection and copy support.

**Architecture:** Schema-driven approach — a `ToolSchemaRegistry` maps each tool name to its display config (summary field, icon, result formatters). The existing `ChatMessage.content` already contains the full `tool.complete` payload (args + result from the gateway), so we parse it to extract summary and result data without changing the data model.

**Tech Stack:** Jetpack Compose (Kotlin 2.3.20), Gson, existing ChatMessage model (Room), Hermes Gateway tool schemas as reference

**Current behavior:**
- `ToolBubble` shows only tool name when collapsed (e.g. "terminal")
- Tapping shows either parsed output (heuristic-based: looks for stdout/stderr or generic fields) or raw JSON
- No text selection support, copy doesn't work on tool calls

**Target behavior:**
- **Collapsed:** `[✓ terminal]` + summary line `$ npm run build` (from args)
- **Expanded:** Clean structured fields — stdout, stderr, exit_code, file path, result count, etc.
- **Raw JSON:** Full raw JSON, accessible via "Show Raw JSON" link
- **Selectable text + copy button** on expanded and raw views

---

## Key insight

The gateway already sends the full payload in `tool.complete`:

```python
# tui_gateway/server.py:_on_tool_complete
payload = {
    "tool_id": "...",
    "name": "terminal",
    "args": {"command": "npm run build", ...},   # <-- for summary line
    "result": {"stdout": "...", "stderr": "...", "exit_code": 0},  # <-- for expanded view
    "duration_s": 10.3,
    "summary": "...",
}
```

The Android app receives this as `WsEvent.ToolComplete(name=name, data=payload)` and the reducer serializes the full payload to `ChatMessage.content` as JSON. **So `args` and `result` are both already in `content`** — we just need to parse them intelligently.

---

## Files

| File | Action |
|------|--------|
| `app/src/main/java/com/m57/hermescontrol/ui/chat/ToolSchemaRegistry.kt` | **Create** |
| `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt` | **Modify** (lines 606-731: rewrite ToolBubble + parseToolOutput + ParsedToolContent) |
| `app/src/test/java/com/m57/hermescontrol/ui/chat/ToolBubbleParsingTest.kt` | **Create** |
| `app/src/main/res/values/strings.xml` | **Modify** (add new string resources) |

---

### Task 1: Create ToolSchemaRegistry

**Objective:** Single source of truth for how each tool should be displayed — what arg field to use for the summary line, what icon to show, and how to format the result.

**Files:**
- Create: `app/src/main/java/com/m57/hermescontrol/ui/chat/ToolSchemaRegistry.kt`

**Step 1: Write the data model + registry**

```kotlin
package com.m57.hermescontrol.ui.chat

/**
 * Schema for displaying a tool's summary line and expanded content.
 * Based on the Hermes Agent tool schemas at /opt/hermes-agent/tools/
 * which define each tool's input args and result fields.
 */
data class ToolDisplayConfig(
    val name: String,
    val summaryArgKey: String? = null,       // e.g. "command" for terminal, "path" for read_file
    val summaryPrefix: String = "",           // e.g. "$ " for terminal, "📄 " for read_file
    val iconEmoji: String = "🔧",             // fallback icon
)

/** Tools ordered by expected frequency of use. */
object ToolSchemaRegistry {

    val knownTools: Map<String, ToolDisplayConfig> = mapOf(
        "terminal" to ToolDisplayConfig(
            name = "terminal",
            summaryArgKey = "command",
            summaryPrefix = "$ ",
            iconEmoji = "💻",
        ),
        "read_file" to ToolDisplayConfig(
            name = "read_file",
            summaryArgKey = "path",
            summaryPrefix = "📄 ",
            iconEmoji = "📄",
        ),
        "write_file" to ToolDisplayConfig(
            name = "write_file",
            summaryArgKey = "path",
            summaryPrefix = "✏️ ",
            iconEmoji = "✏️",
        ),
        "patch" to ToolDisplayConfig(
            name = "patch",
            summaryArgKey = "path",
            summaryPrefix = "🔧 ",
            iconEmoji = "🔧",
        ),
        "search_files" to ToolDisplayConfig(
            name = "search_files",
            summaryArgKey = "pattern",
            summaryPrefix = "🔍 ",
            iconEmoji = "🔍",
        ),
        "web_search" to ToolDisplayConfig(
            name = "web_search",
            summaryArgKey = "query",
            summaryPrefix = "🌐 ",
            iconEmoji = "🌐",
        ),
        "browser_navigate" to ToolDisplayConfig(
            name = "browser_navigate",
            summaryArgKey = "url",
            summaryPrefix = "🌍 ",
            iconEmoji = "🌍",
        ),
        "browser_click" to ToolDisplayConfig(
            name = "browser_click",
            summaryArgKey = "ref",
            summaryPrefix = "🖱 ",
            iconEmoji = "🖱",
        ),
        "browser_snapshot" to ToolDisplayConfig(
            name = "browser_snapshot",
            summaryArgKey = null,       // no single arg to summarize
            iconEmoji = "📋",
        ),
        "clarify" to ToolDisplayConfig(
            name = "clarify",
            summaryArgKey = "question",
            summaryPrefix = "💬 ",
            iconEmoji = "💬",
        ),
        "delegate_task" to ToolDisplayConfig(
            name = "delegate_task",
            summaryArgKey = "goal",
            summaryPrefix = "🔄 ",
            iconEmoji = "🔄",
        ),
        "execute_code" to ToolDisplayConfig(
            name = "execute_code",
            summaryArgKey = "code",        // code is multi-line, truncate
            summaryPrefix = "▶️ ",
            iconEmoji = "▶️",
        ),
    )

    fun getDisplayConfig(toolName: String?): ToolDisplayConfig =
        toolName?.let { knownTools[it] } ?: ToolDisplayConfig(name = toolName ?: "tool")
}
```

**Step 2: Verify it compiles conceptually** (manual check — no Android SDK locally)

```bash
./ktlint --format app/src/main/java/com/m57/hermescontrol/ui/chat/ToolSchemaRegistry.kt
```

**Step 3: Commit**

```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ToolSchemaRegistry.kt
git commit -m "feat(#210): add ToolSchemaRegistry with display config for each tool"
```

---

### Task 2: Rewrite parseToolOutput to be schema-aware

**Objective:** Replace the fragile heuristic-based `parseToolOutput` with a schema-aware parser that extracts args and result from the JSON content based on the tool name.

**Files:**
- Modify: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt` (replace `ParsedToolOutput` data class and `parseToolOutput` function)

**Step 1: Replace `ParsedToolOutput` data class (lines 425-434) with a richer model:**

```kotlin
/**
 * Cleanly parsed representation of a tool call, extracted from the
 * tool.complete JSON payload (which contains both args and result).
 */
data class ParsedToolData(
    val toolName: String,
    val args: Map<String, Any?> = emptyMap(),
    val result: Map<String, Any?> = emptyMap(),
    val isTerminal: Boolean = false,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val summaryText: String? = null,   // one-line summary from args
    val durationSec: Double? = null,
    val mainOutput: String? = null,    // for non-terminal tools: content/result/text
    val extraFields: Map<String, String> = emptyMap(),
    val isRunning: Boolean = false,
)
```

**Step 2: Replace `parseToolOutput` function (lines 436-512):**

```kotlin
private fun parseToolOutput(content: String, toolName: String?, isRunning: Boolean): ParsedToolData? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
    return try {
        val element = com.google.gson.JsonParser.parseString(trimmed)
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject

        // Extract args sub-object if present (gateway sends it in tool.complete)
        val argsObj = obj.get("args")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject
        val args: Map<String, Any?> = argsObj?.entrySet()?.associate {
            it.key to (if (it.value.isJsonPrimitive) it.value.asString else it.value.toString())
        } ?: emptyMap()

        // Extract result sub-object if present
        val resultObj = obj.get("result")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject

        // Build summary line from args using ToolSchemaRegistry
        val config = ToolSchemaRegistry.getDisplayConfig(toolName)
        val summaryText = if (config.summaryArgKey != null) {
            val raw = args[config.summaryArgKey]?.toString() ?: ""
            val truncated = if (raw.length > 100) raw.take(100) + "…" else raw
            if (truncated.isNotBlank()) "${config.summaryPrefix}$truncated" else null
        } else null

        // Check if this looks like a terminal result
        val hasStdout = resultObj?.has("stdout") == true
        val hasStderr = resultObj?.has("stderr") == true
        val hasExitCode = resultObj?.has("exit_code") == true || resultObj?.has("exitCode") == true

        if (hasStdout || hasStderr || hasExitCode) {
            // Terminal-style display
            val stdout = resultObj?.get("stdout")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            val stderr = resultObj?.get("stderr")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            val exitCode = (resultObj?.get("exit_code") ?: resultObj?.get("exitCode"))?.takeIf { !it.isJsonNull }?.asInt
            val error = resultObj?.get("error")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble

            ParsedToolData(
                toolName = toolName ?: "",
                args = args,
                result = resultObj?.entrySet()?.associate { it.key to it.value.toString() } ?: emptyMap(),
                isTerminal = true,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                error = error,
                summaryText = summaryText,
                durationSec = duration,
                isRunning = isRunning,
            )
        } else {
            // Generic tool display
            val mainOutput = resultObj?.let { obj ->
                (obj.get("output") ?: obj.get("result") ?: obj.get("content") ?: obj.get("text"))
                    ?.takeIf { !it.isJsonNull }
                    ?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
                    ?.takeIf { it.isNotEmpty() }
            }

            val extraFields = mutableMapOf<String, String>()
            resultObj?.entrySet()?.forEach { (key, value) ->
                if (key != "output" && key != "result" && key != "content" && key != "text" && !value.isJsonNull) {
                    val valStr = if (value.isJsonPrimitive) value.asString else value.toString()
                    if (valStr.isNotEmpty() && valStr != "null") {
                        extraFields[key] = valStr
                    }
                }
            }

            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble

            ParsedToolData(
                toolName = toolName ?: "",
                args = args,
                result = resultObj?.entrySet()?.associate { it.key to it.value.toString() } ?: emptyMap(),
                summaryText = summaryText,
                mainOutput = mainOutput,
                extraFields = extraFields,
                durationSec = duration,
                isRunning = isRunning,
            )
        }
    } catch (e: Exception) {
        null
    }
}
```

**Step 3: Update the call site (line 616):**

Change:
```kotlin
val parsedOutput = remember(message.content) { parseToolOutput(message.content) }
```
To:
```kotlin
val parsedOutput = remember(message.content, message.toolName, message.toolStatus) {
    parseToolOutput(message.content, message.toolName, message.toolStatus == ToolStatus.RUNNING)
}
```

**Step 4: Run ktlint:**

```bash
./ktlint --format app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt
```

**Step 5: Commit**

```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt
git commit -m "refactor(#210): schema-aware parseToolOutput with args extraction"
```

---

### Task 3: Rewrite ToolBubble with three display states

**Objective:** Replace the `ToolBubble` composable (lines 606-731) with the new three-state design: collapsed (tool name + summary line), expanded (clean structured info), raw JSON toggle.

**Files:**
- Modify: `app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt` (replace `ToolBubble`, replace `ParsedToolContent`)

**Step 1: Replace `ToolBubble` composable (approx lines 606-731):**

```kotlin
@Composable
private fun ToolBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    val chipColor = if (isDarkTheme) ToolChipColor else ToolChipColorLight
    val contentColor = if (isDarkTheme) Color.White else Color.Black

    val parsed = remember(message.content, message.toolName, message.toolStatus) {
        parseToolOutput(message.content, message.toolName, message.toolStatus == ToolStatus.RUNNING)
    }
    val config = ToolSchemaRegistry.getDisplayConfig(message.toolName)

    val clipboardManager = LocalClipboardManager.current
    var showCopyButton by remember { mutableStateOf(false) }

    // Auto-dismiss copy button
    LaunchedEffect(showCopyButton) {
        if (showCopyButton) {
            delay(4000)
            showCopyButton = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Card(
            onClick = { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = chipColor),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .animateContentSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                // ── Header row: icon + tool name + summary line ──
                HeaderRow(
                    message = message,
                    config = config,
                    parsed = parsed,
                    contentColor = contentColor,
                )

                // ── Summary line (always visible) ──
                if (!expanded && parsed?.summaryText != null) {
                    Text(
                        text = parsed.summaryText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 22.dp),
                    )
                }

                // ── Expanded content ──
                if (expanded) {
                    Spacer(modifier = Modifier.height(6.dp))

                    if (showRawJson) {
                        // Raw JSON view — selectable + copy button
                        Box {
                            SelectionContainer {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = contentColor.copy(alpha = 0.8f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                )
                            }
                            // Copy button overlay
                            CopyButton(
                                visible = showCopyButton,
                                textToCopy = message.content,
                                clipboardManager = clipboardManager,
                                onShow = { showCopyButton = true },
                                onHide = { showCopyButton = false },
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.chat_tool_show_parsed),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            ),
                            modifier = Modifier
                                .testTag("chat_tool_show_parsed")
                                .clickable(role = Role.Button) { showRawJson = false },
                        )
                    } else if (parsed != null) {
                        // Clean structured expanded view
                        Box {
                            ExpandedToolContent(parsed, contentColor)
                            CopyButton(
                                visible = showCopyButton,
                                textToCopy = message.content,
                                clipboardManager = clipboardManager,
                                onShow = { showCopyButton = true },
                                onHide = { showCopyButton = false },
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.chat_tool_show_raw),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            ),
                            modifier = Modifier
                                .testTag("chat_tool_show_raw")
                                .clickable(role = Role.Button) { showRawJson = true },
                        )
                    } else {
                        // Unparseable content — show raw JSON
                        Box {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = contentColor.copy(alpha = 0.8f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                            )
                            CopyButton(
                                visible = showCopyButton,
                                textToCopy = message.content,
                                clipboardManager = clipboardManager,
                                onShow = { showCopyButton = true },
                                onHide = { showCopyButton = false },
                            )
                        }
                    }
                }

                // ── Timestamp ──
                Text(
                    text = formatTimestamp(message.timestamp),
                    color = contentColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(
    message: ChatMessage,
    config: ToolDisplayConfig,
    parsed: ParsedToolData?,
    contentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status icon or spinner
        if (message.toolStatus == ToolStatus.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            val icon = when (message.toolStatus) {
                ToolStatus.COMPLETED -> Icons.Filled.CheckCircle
                ToolStatus.FAILED -> Icons.Filled.Error
                else -> Icons.Filled.Build
            }
            val tint = when (message.toolStatus) {
                ToolStatus.COMPLETED -> StatusGreen
                ToolStatus.FAILED -> StatusRed
                else -> contentColor.copy(alpha = 0.6f)
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }

        Text(
            text = message.toolName ?: stringResource(R.string.chat_tool_fallback),
            style = MaterialTheme.typography.labelMedium.copy(
                color = contentColor,
                fontFamily = FontFamily.Monospace,
            ),
        )
    }
}

@Composable
private fun CopyButton(
    visible: Boolean,
    textToCopy: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onShow: () -> Unit,
    onHide: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp),
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 6.dp,
        ) {
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(textToCopy))
                    onHide()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.content_desc_copy),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
```

**Step 2: Update `ExpandedToolContent` (rename from `ParsedToolContent`, lines 515-600):**

```kotlin
@Composable
private fun ExpandedToolContent(
    parsed: ParsedToolData,
    contentColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Summary line at top of expanded view
        if (parsed.summaryText != null) {
            Text(
                text = parsed.summaryText,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = contentColor.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        if (parsed.isTerminal) {
            // ── Terminal output ──
            parsed.stdout?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = contentColor.copy(alpha = 0.9f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
            }
            parsed.stderr?.let {
                Text(
                    text = stringResource(R.string.chat_tool_stderr, it),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = StatusRed.copy(alpha = 0.9f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
            }
            parsed.error?.let {
                Text(
                    text = stringResource(R.string.chat_tool_execution_error, it),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = StatusRed,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
            }
            parsed.exitCode?.let { code ->
                if (code != 0) {
                    Text(
                        text = stringResource(R.string.chat_tool_exit_code, code),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = StatusRed,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }

            // Duration footer for terminal
            parsed.durationSec?.let { dur ->
                Text(
                    text = "Duration: ${"%.1f".format(dur)}s",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = contentColor.copy(alpha = 0.5f),
                    ),
                )
            }
        } else {
            // ── Generic tool output ──
            parsed.mainOutput?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = contentColor.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                    ),
                )
            }
            parsed.extraFields.forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "$key:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = contentColor.copy(alpha = 0.6f),
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                        ),
                    )
                }
            }

            // Duration footer
            parsed.durationSec?.let { dur ->
                Text(
                    text = "Duration: ${"%.1f".format(dur)}s",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = contentColor.copy(alpha = 0.5f),
                    ),
                )
            }
        }
    }
}
```

**Step 3: Add new imports to the top of ChatBubble.kt:**

```kotlin
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.style.TextOverflow
```

(`SelectionContainer` already imported at line 30. Add `TextOverflow`.)

**Step 4: Add string resources to `strings.xml`:**

```xml
<string name="chat_tool_duration">Duration: %1$.1fs</string>
<string name="content_desc_copy">Copy to clipboard</string>
```

**Step 5: Run ktlint:**

```bash
./ktlint --format app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt
```

**Step 6: Commit**

```bash
git add app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt app/src/main/res/values/strings.xml
git commit -m "feat(#210): three-state ToolBubble with summary line, clean expanded view, copy support"
```

---

### Task 4: Add tests for tool output parsing

**Objective:** Unit tests for `parseToolOutput` with various tool payloads to ensure the extraction of summary, args, and result fields works correctly.

**Files:**
- Create: `app/src/test/java/com/m57/hermescontrol/ui/chat/ToolBubbleParsingTest.kt`

**Step 1: Write test class:**

```kotlin
package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBubbleParsingTest {

    // ── Terminal ──────────────────────────────────────────────

    @Test
    fun testParseTerminal_withStdout() {
        val json = """{
            "tool_id": "call_001",
            "name": "terminal",
            "args": {"command": "npm run build"},
            "result": {"stdout": "Build succeeded!", "exit_code": 0},
            "duration_s": 5.2
        }"""
        val parsed = parseToolOutput(json, "terminal", false)
        assertNotNull(parsed)
        assertEquals("terminal", parsed!!.toolName)
        assertTrue(parsed.isTerminal)
        assertEquals("Build succeeded!", parsed.stdout)
        assertEquals(0, parsed.exitCode)
        assertEquals(5.2, parsed.durationSec, 0.01)
        assertEquals("$ npm run build", parsed.summaryText)
    }

    @Test
    fun testParseTerminal_withStderr() {
        val json = """{
            "tool_id": "call_002",
            "name": "terminal",
            "args": {"command": "rm -rf /"},
            "result": {"stderr": "Permission denied", "exit_code": 1}
        }"""
        val parsed = parseToolOutput(json, "terminal", false)
        assertNotNull(parsed)
        assertTrue(parsed!!.isTerminal)
        assertEquals("Permission denied", parsed.stderr)
        assertEquals(1, parsed.exitCode)
        assertEquals("$ rm -rf /", parsed.summaryText)
    }

    // ── File tools ────────────────────────────────────────────

    @Test
    fun testParseReadFile() {
        val json = """{
            "tool_id": "call_003",
            "name": "read_file",
            "args": {"path": "/opt/hermes/config.yaml"},
            "result": {"content": "port: 8080\nhost: 0.0.0.0"}
        }"""
        val parsed = parseToolOutput(json, "read_file", false)
        assertNotNull(parsed)
        assertFalse(parsed!!.isTerminal)
        assertEquals("📄 /opt/hermes/config.yaml", parsed.summaryText)
        assertEquals("port: 8080\nhost: 0.0.0.0", parsed.mainOutput)
    }

    @Test
    fun testParseWriteFile() {
        val json = """{
            "tool_id": "call_004",
            "name": "write_file",
            "args": {"path": "/tmp/output.txt"},
            "result": {"content": "File written successfully"}
        }"""
        val parsed = parseToolOutput(json, "write_file", false)
        assertNotNull(parsed)
        assertEquals("✏️ /tmp/output.txt", parsed!!.summaryText)
    }

    @Test
    fun testParsePatch() {
        val json = """{
            "tool_id": "call_005",
            "name": "patch",
            "args": {"path": "src/main.kt"},
            "result": {"success": true}
        }"""
        val parsed = parseToolOutput(json, "patch", false)
        assertNotNull(parsed)
        assertEquals("🔧 src/main.kt", parsed!!.summaryText)
    }

    @Test
    fun testParseSearchFiles() {
        val json = """{
            "tool_id": "call_006",
            "name": "search_files",
            "args": {"pattern": "*.kt", "path": "/opt/hermes-mobile"},
            "result": {"matches": ["file1.kt", "file2.kt"]}
        }"""
        val parsed = parseToolOutput(json, "search_files", false)
        assertNotNull(parsed)
        assertEquals("🔍 *.kt", parsed!!.summaryText)
    }

    // ── Web / Browser ─────────────────────────────────────────

    @Test
    fun testParseWebSearch() {
        val json = """{
            "tool_id": "call_007",
            "name": "web_search",
            "args": {"query": "Kotlin Coroutines guide"},
            "result": {"results": ["..."]}
        }"""
        val parsed = parseToolOutput(json, "web_search", false)
        assertNotNull(parsed)
        assertEquals("🌐 Kotlin Coroutines guide", parsed!!.summaryText)
    }

    @Test
    fun testParseBrowserNavigate() {
        val json = """{
            "tool_id": "call_008",
            "name": "browser_navigate",
            "args": {"url": "https://example.com"},
            "result": {"title": "Example Domain"}
        }"""
        val parsed = parseToolOutput(json, "browser_navigate", false)
        assertNotNull(parsed)
        assertEquals("🌍 https://example.com", parsed!!.summaryText)
    }

    @Test
    fun testParseClarify() {
        val json = """{
            "tool_id": "call_009",
            "name": "clarify",
            "args": {"question": "Which environment?"},
            "result": {"user_response": "staging"}
        }"""
        val parsed = parseToolOutput(json, "clarify", false)
        assertNotNull(parsed)
        assertEquals("💬 Which environment?", parsed!!.summaryText)
    }

    // ── Running state ─────────────────────────────────────────

    @Test
    fun testParseRunningState() {
        val json = """{"tool_id": "call_010", "name": "terminal", "args": {"command": "sleep 10"}}"""
        val parsed = parseToolOutput(json, "terminal", true)
        assertNotNull(parsed)
        assertTrue(parsed!!.isRunning)
    }

    // ── Unknown tool fallback ─────────────────────────────────

    @Test
    fun testParseUnknownTool() {
        val json = """{
            "tool_id": "call_011",
            "name": "weird_tool",
            "args": {"input": "data"},
            "result": {"output": "processed"}
        }"""
        val parsed = parseToolOutput(json, "weird_tool", false)
        assertNotNull(parsed)
        assertNull(parsed!!.summaryText)  // no known config → no summary
        assertEquals("processed", parsed.mainOutput)
    }
}
```

**Step 2: Verify tests conceptually** (run if possible, may need SDK)

**Step 3: Commit**

```bash
git add app/src/test/java/com/m57/hermescontrol/ui/chat/ToolBubbleParsingTest.kt
git commit -m "test(#210): add ToolBubbleParsingTest for schema-aware parsing"
```

---

### Task 5: Finalize — ktlint, verify, push, PR

**Step 1: Run ktlint on all changed files:**

```bash
cd /opt/hermes-mobile
./ktlint --format \
  app/src/main/java/com/m57/hermescontrol/ui/chat/ChatBubble.kt \
  app/src/main/java/com/m57/hermescontrol/ui/chat/ToolSchemaRegistry.kt \
  app/src/test/java/com/m57/hermescontrol/ui/chat/ToolBubbleParsingTest.kt
```

**Step 2: Push and open PR:**

```bash
git checkout main && git pull origin main
git checkout -b feat/issue-210-tool-bubble-redesign
git add -A
git commit -m "feat(#210): schema-driven tool bubble redesign with summary line and copy support"
git push -u origin HEAD
gh pr create \
  --title "feat(#210): schema-driven tool bubble redesign" \
  --body "## Summary

Redesigns the ToolBubble with three display states:
1. **Collapsed** — tool icon + name + one-line summary (e.g. \`$ npm run build\`)
2. **Expanded** — clean structured fields (stdout, stderr, exit code, etc.)
3. **Raw JSON** — full raw payload with toggle back

Adds text selection (SelectionContainer) and copy button to expanded/raw views.

## Changes

- **ToolSchemaRegistry.kt** — new: maps tool names to display config (icon, summary field)
- **ChatBubble.kt** — rewrote ToolBubble, parseToolOutput, ParsedToolContent → ExpandedToolContent
- **strings.xml** — added duration string resource
- **ToolBubbleParsingTest.kt** — new: 12 test cases for parsing various tool payloads

## Verification

- Summary line shows the key arg (command, path, query, etc.) without needing a tap
- Expanded view shows clean structured fields per tool type
- Raw JSON accessible via toggle link
- Text is selectable and copyable in expanded and raw views

Closes #210"
```

---

## Summary of changes

| File | Action | Lines |
|------|--------|-------|
| `ToolSchemaRegistry.kt` | **Create** | ~70 |
| `ChatBubble.kt` | **Modify** | ~200 (replace ToolBubble, parseToolOutput, ParsedToolContent) |
| `strings.xml` | **Modify** | +2 lines |
| `ToolBubbleParsingTest.kt` | **Create** | ~140 |

## Verification checklist

- [ ] Collapsed tool bubble shows tool name + summary line (e.g. `$ npm run build`)
- [ ] Tapping expands to show clean structured info (not raw JSON)
- [ ] "Show Raw JSON" link toggles to raw payload
- [ ] "Show Parsed" link toggles back
- [ ] Long-press or selection works on expanded and raw views
- [ ] Copy button appears on expanded/raw views
- [ ] Running tools show spinner without summary
- [ ] Failed tools show error state
- [ ] Unknown tools fall back gracefully (no summary, raw JSON default)
- [ ] ktlint passes on all files

## Risks & tradeoffs

- **No Android SDK locally** — can't run `./gradlew test` or compile. Must push to CI and verify there. Keep commits small.
- **parseToolOutput is now a module-level function** (not private) — needed for unit tests. This is fine since ChatBubble.kt is a single-file module already.
- **The `content` JSON shape depends on the gateway** — we assume `args` and `result` sub-objects are always present in `tool.complete`. Looking at the gateway code (`_on_tool_complete`), this is guaranteed. The `tool.start` payload has a different shape (`context`, `args_text`), but the reducer overwrites it with the `tool.complete` data, so this is safe.
- **No test for Compose rendering** — Jetpack Compose UI tests need Android emulator/device. We only unit-test the parsing logic.
