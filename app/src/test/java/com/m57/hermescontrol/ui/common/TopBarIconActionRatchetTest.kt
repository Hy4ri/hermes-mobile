package com.m57.hermescontrol.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ratchet guard for #1651: top-bar icon actions go through [HermesIconButton],
 * whose single localized label drives both the long-press tooltip and the
 * accessibility description.
 *
 * Each file in [MIGRATED_TOP_BARS] must contain no raw `IconButton(` call.
 * Adding a new icon action to one of these files must use [HermesIconButton]
 * (or consciously extend the migration first). Migrating another screen's top
 * bar is a follow-up PR: add its file to the list when its icons convert.
 *
 * Scanner limitations (same class of blind spots as RawRpcParamsRatchetTest):
 * a wrapper spelled differently than `HermesIconButton`, or a call built via
 * reflection, is invisible. `IconButton(` inside comments or string literals
 * does not count — the stripper below removes both.
 */
class TopBarIconActionRatchetTest {
    @Test
    fun migratedTopBarsHaveNoRawIconButtonCalls() {
        val rootDir = resolveMainJavaRoot()
        val offenders =
            MIGRATED_TOP_BARS.flatMap { relativePath ->
                val file = File(rootDir, relativePath)
                assertTrue("Missing guarded source file: $relativePath", file.isFile)
                countRawIconButtons(file.readText(), relativePath)
            }

        assertEquals(
            "Raw IconButton( found in a migrated top bar — route the action through " +
                "HermesIconButton so it gets a long-press tooltip and a matching " +
                "accessibility label (#1651):",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun scannerSelfTestDetectsRawCallsAndIgnoresWrappedAndCommentedOnes() {
        val sample =
            """
            // IconButton( in a line comment is ignored
            /* IconButton( in a block comment too */
            val label = "IconButton( in a string is ignored"
            HermesIconButton(
                imageVector = Icons.Filled.Add,
                label = label,
                onClick = {},
            )
            IconButton(onClick = {})
            """.trimIndent()

        val detected = countRawIconButtons(sample, "sample.kt")

        assertEquals(listOf("sample.kt:9"), detected)
    }

    private companion object {
        /** Top bars migrated to [HermesIconButton]; extend per follow-up PR. */
        val MIGRATED_TOP_BARS =
            listOf(
                "ui/common/HermesScaffold.kt",
                "ui/sessions/SessionsScreen.kt",
                "ui/chat/ChatScreen.kt",
            )

        fun countRawIconButtons(
            code: String,
            relativePath: String,
        ): List<String> {
            val cleaned = stripCommentsAndStrings(code)
            return Regex("""(?<!Hermes)IconButton\(""")
                .findAll(cleaned)
                .map { match ->
                    val line = cleaned.substring(0, match.range.first).count { it == '\n' } + 1
                    "$relativePath:$line"
                }.toList()
        }

        fun stripCommentsAndStrings(text: String): String {
            val result = StringBuilder(text.length)
            var i = 0
            val n = text.length
            var inString = false
            var inTripleString = false
            var inChar = false

            while (i < n) {
                if (!inString && !inTripleString && !inChar) {
                    when {
                        text.startsWith("\"\"\"", i) -> {
                            inTripleString = true
                            result.append("\"\"\"")
                            i += 3
                        }

                        text[i] == '"' -> {
                            inString = true
                            result.append(' ')
                            i++
                        }

                        text[i] == '\'' -> {
                            inChar = true
                            result.append(' ')
                            i++
                        }

                        text.startsWith("//", i) -> {
                            var j = text.indexOf('\n', i)
                            if (j == -1) j = n
                            while (i < j) {
                                result.append(' ')
                                i++
                            }
                        }

                        text.startsWith("/*", i) -> {
                            var depth = 1
                            var j = i + 2
                            while (j < n && depth > 0) {
                                when {
                                    text.startsWith("/*", j) -> {
                                        depth++
                                        j += 2
                                    }

                                    text.startsWith("*/", j) -> {
                                        depth--
                                        j += 2
                                    }

                                    else -> {
                                        j++
                                    }
                                }
                            }
                            while (i < j) {
                                result.append(if (text[i] == '\n') '\n' else ' ')
                                i++
                            }
                        }

                        else -> {
                            result.append(text[i])
                            i++
                        }
                    }
                } else if (inTripleString) {
                    if (text.startsWith("\"\"\"", i)) {
                        inTripleString = false
                        result.append(' ')
                        i += 3
                    } else {
                        result.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                } else {
                    // Inside a regular string or char literal: blank the content,
                    // preserve newlines so reported line numbers stay honest.
                    if (text[i] == '\\' && i + 1 < n) {
                        result.append(' ')
                        i += 2
                    } else {
                        val c = text[i]
                        result.append(if (c == '\n') '\n' else ' ')
                        if ((!inString && c == '\'') || (inString && c == '"')) {
                            inChar = false
                            inString = false
                        }
                        i++
                    }
                }
            }
            return result.toString()
        }

        fun resolveMainJavaRoot(): File {
            val userDir = File(System.getProperty("user.dir") ?: ".")
            val candidates =
                listOf(
                    File(userDir, "app/src/main/java/com/m57/hermescontrol"),
                    File(userDir, "src/main/java/com/m57/hermescontrol"),
                    File(userDir, "../app/src/main/java/com/m57/hermescontrol"),
                )
            return candidates.firstOrNull { it.isDirectory }
                ?: error("Could not resolve app/src/main/java/com/m57/hermescontrol from $userDir")
        }
    }
}
