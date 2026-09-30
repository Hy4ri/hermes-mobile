package com.m57.hermescontrol.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `servers decode tools as null, list, or filter object`() {
        val body =
            """{"servers":[
              {"name":"a","enabled":true,"tools":null},
              {"name":"b","enabled":true,"tools":["x","y"]},
              {"name":"c","enabled":true,"tools":{"exclude":["docs","*_radar*"]}},
              {"name":"d","enabled":true,"tools":{"include":["one","two","three"]}}
            ]}"""
        val servers = json.decodeFromString<McpServersResponse>(body).servers
        assertEquals(listOf<Int?>(null, 2, null, 3), servers.map { it.toolCount })
    }

    @Test
    fun `catalog entry decodes backend required_env and installed flags`() {
        val body =
            """{"entries":[{"name":"gh","description":"d","source":"nous","transport":"stdio",
              "required_env":[{"name":"GH_TOKEN","prompt":"Token","required":true}],
              "installed":true,"enabled":false,"needs_install":false}],"diagnostics":[]}"""
        val entry = json.decodeFromString<McpCatalogResponse>(body).entries.single()
        assertTrue(entry.installed)
        assertEquals("GH_TOKEN", entry.env?.single()?.key)
        assertEquals("Token", entry.env?.single()?.label)
        assertNull(entry.url)
    }
}
