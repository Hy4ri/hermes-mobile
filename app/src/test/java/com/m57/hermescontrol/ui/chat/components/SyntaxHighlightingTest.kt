package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.theme.CodeKeyword
import com.m57.hermescontrol.theme.CodeNumber
import com.m57.hermescontrol.theme.CodePunctuation
import com.m57.hermescontrol.theme.CodeString
import org.junit.Assert.assertNotNull
import org.junit.Test

class SyntaxHighlightingTest {
    @Test
    fun `highlightSyntax highlights json keys as keyword and values as string`() {
        val json = """{"status": "running", "code": 0}"""
        val result = highlightSyntax(json)

        val styles = result.spanStyles
        val keySpan = styles.find { it.item.color == CodeKeyword }
        assertNotNull("JSON key should be highlighted with CodeKeyword", keySpan)

        val stringSpan = styles.find { it.item.color == CodeString }
        assertNotNull("String value should be highlighted with CodeString", stringSpan)

        val numberSpan = styles.find { it.item.color == CodeNumber }
        assertNotNull("Number value should be highlighted with CodeNumber", numberSpan)

        val punctSpan = styles.find { it.item.color == CodePunctuation }
        assertNotNull("Colon/braces should be highlighted with CodePunctuation", punctSpan)
    }
}
