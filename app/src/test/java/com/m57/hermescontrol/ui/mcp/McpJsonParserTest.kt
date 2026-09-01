package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.model.McpServerTestResponse
import com.m57.hermescontrol.data.model.McpServerToolInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonParserTest {
    @Test
    fun testParseClaudeDesktopMcpServersWrapper() {
        val json =
            """
            {
              "mcpServers": {
                "sqlite": {
                  "command": "uvx",
                  "args": ["mcp-server-sqlite", "--db-path", "/path/to/db.sqlite"],
                  "env": {
                    "DEBUG": "1"
                  }
                },
                "github": {
                  "url": "https://api.github.com/mcp",
                  "headers": {
                    "Authorization": "Bearer gh_secret_123"
                  }
                }
              }
            }
            """.trimIndent()

        val parsed = McpJsonParser.parse(json)
        assertEquals(2, parsed.size)

        val sqlite = parsed.find { it.name == "sqlite" }
        assertNotNull(sqlite)
        assertEquals("uvx", sqlite?.command)
        assertEquals(listOf("mcp-server-sqlite", "--db-path", "/path/to/db.sqlite"), sqlite?.args)
        assertEquals(mapOf("DEBUG" to "1"), sqlite?.env)
        assertEquals("none", sqlite?.auth)

        val github = parsed.find { it.name == "github" }
        assertNotNull(github)
        assertEquals("https://api.github.com/mcp", github?.url)
        assertEquals("header", github?.auth)
        assertEquals("gh_secret_123", github?.bearerToken)
    }

    @Test
    fun testParseSnakeCaseMcpServersWrapper() {
        val json =
            """
            {
              "mcp_servers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
                }
              }
            }
            """.trimIndent()

        val parsed = McpJsonParser.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("filesystem", parsed[0].name)
        assertEquals("npx", parsed[0].command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), parsed[0].args)
    }

    @Test
    fun testParseDirectServerMap() {
        val json =
            """
            {
              "postgres": {
                "command": "npx",
                "args": ["-y", "@modelcontextprotocol/server-postgres", "postgresql://localhost/db"]
              }
            }
            """.trimIndent()

        val parsed = McpJsonParser.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("postgres", parsed[0].name)
        assertEquals("npx", parsed[0].command)
    }

    @Test
    fun testParseSingleServerObject() {
        val json =
            """
            {
              "name": "custom-tools",
              "command": "python3",
              "args": ["-m", "mcp_server"],
              "auth": "oauth"
            }
            """.trimIndent()

        val parsed = McpJsonParser.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("custom-tools", parsed[0].name)
        assertEquals("python3", parsed[0].command)
        assertEquals("oauth", parsed[0].auth)
    }

    @Test
    fun testParseInvalidJsonReturnsEmpty() {
        val parsed = McpJsonParser.parse("not json at all")
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun testTokenOverheadEstimator() {
        val tools =
            listOf(
                McpServerToolInfo(name = "read_file", schema_chars = 400),
                McpServerToolInfo(name = "write_file", schema_chars = 401),
                McpServerToolInfo(name = "no_chars", schema_chars = null),
            )

        // ceil(400/4) = 100, ceil(401/4) = 101 -> sum = 201
        val est = McpTokenEstimator.estimateTokens(tools)
        assertEquals(201, est)

        val formatted = McpTokenEstimator.formatTokenOverhead(tools.size, est)
        assertEquals("3 tools • ~201 tokens", formatted)
    }

    @Test
    fun testTokenOverheadLargeFormatting() {
        val tools =
            listOf(
                McpServerToolInfo(name = "tool_huge", schema_chars = 12800),
            )

        // ceil(12800/4) = 3200 tokens -> ~3.2k tokens
        val est = McpTokenEstimator.estimateTokens(tools)
        assertEquals(3200, est)
        val formatted = McpTokenEstimator.formatTokenOverhead(49, est)
        assertEquals("49 tools • ~3.2k tokens", formatted)
    }

    @Test
    fun testTokenOverheadNoSchemaChars() {
        val tools =
            listOf(
                McpServerToolInfo(name = "tool1", schema_chars = null),
                McpServerToolInfo(name = "tool2", schema_chars = 0),
            )

        val est = McpTokenEstimator.estimateTokens(tools)
        assertNull(est)
        assertEquals("2 tools", McpTokenEstimator.formatTokenOverhead(2, est))
    }

    @Test
    fun testHealthStatusResolution() {
        // In flight
        assertEquals(
            McpHealthStatus.TESTING,
            McpHealthStatus.resolve(
                isTesting = true,
                testResult = null,
                serverStatus = "ok",
                serverError = null,
            ),
        )

        // Healthy result
        assertEquals(
            McpHealthStatus.HEALTHY,
            McpHealthStatus.resolve(
                isTesting = false,
                testResult = McpServerTestResponse(ok = true),
                serverStatus = null,
                serverError = null,
            ),
        )

        // Auth needed
        assertEquals(
            McpHealthStatus.AUTH_REQUIRED,
            McpHealthStatus.resolve(
                isTesting = false,
                testResult = McpServerTestResponse(ok = false, error = "OAuth authentication required"),
                serverStatus = null,
                serverError = null,
            ),
        )

        // Error
        assertEquals(
            McpHealthStatus.ERROR,
            McpHealthStatus.resolve(
                isTesting = false,
                testResult = McpServerTestResponse(ok = false, error = "Process exited with 1"),
                serverStatus = null,
                serverError = null,
            ),
        )

        // Fallback from status
        assertEquals(
            McpHealthStatus.HEALTHY,
            McpHealthStatus.resolve(
                isTesting = false,
                testResult = null,
                serverStatus = "running",
                serverError = null,
            ),
        )
    }
}
