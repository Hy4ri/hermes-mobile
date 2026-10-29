package com.m57.hermescontrol.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HermesScaffoldPaddingContractTest {
    @Test
    fun hermesScaffoldCallersDoNotReapplyContentPaddingValues() {
        val sourceRoot = resolveSourceRoot()
        val offenders =
            sourceRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.name == "HermesScaffold.kt" }
                .mapNotNull { file ->
                    val relativePath = file.relativeTo(sourceRoot).invariantSeparatorsPath
                    if (hasDoublePaddingOffense(file.readText())) {
                        relativePath
                    } else {
                        null
                    }
                }.sorted()
                .toList()

        assertTrue(
            "HermesScaffold already applies scaffold insets; callers must not reapply paddingValues: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun scannerSelfTestDetectsReappliedPaddingAndIgnoresValidOrCommentedCode() {
        val positiveSample =
            """
            HermesScaffold(
                title = { Text("Test") },
            ) { paddingValues ->
                LoadingState(modifier = Modifier.padding(paddingValues))
            }
            """.trimIndent()

        val negativeSample =
            """
            // HermesScaffold(...) { paddingValues ->
            //     LoadingState(modifier = Modifier.padding(paddingValues))
            // }
            HermesScaffold(
                title = { Text("Test") },
            ) { paddingValues ->
                LoadingState(modifier = Modifier)
            }
            """.trimIndent()

        assertTrue("Positive sample must detect double padding", hasDoublePaddingOffense(positiveSample))
        assertTrue("Negative sample must not detect double padding", !hasDoublePaddingOffense(negativeSample))
    }

    private companion object {
        fun hasDoublePaddingOffense(code: String): Boolean {
            val cleaned = stripCommentsAndStrings(code)
            val usesHermesScaffold = "HermesScaffold(" in cleaned
            val reappliesPaddingValues =
                Regex("""\.padding\(\s*(paddingValues|padding)\s*\)""").containsMatchIn(cleaned)
            return usesHermesScaffold && reappliesPaddingValues
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

        fun resolveSourceRoot(): File {
            val userDir = File(System.getProperty("user.dir") ?: ".")
            return sequenceOf(
                File(userDir, "app/src/main/java/com/m57/hermescontrol"),
                File(userDir, "src/main/java/com/m57/hermescontrol"),
                File(userDir, "../app/src/main/java/com/m57/hermescontrol"),
            ).firstOrNull { it.isDirectory }
                ?: error("Could not resolve app/src/main/java/com/m57/hermescontrol from $userDir")
        }
    }
}
