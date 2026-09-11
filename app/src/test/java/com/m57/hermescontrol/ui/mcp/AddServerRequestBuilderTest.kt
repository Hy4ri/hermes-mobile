package com.m57.hermescontrol.ui.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddServerRequestBuilderTest {
    @Test
    fun `build HTTP mode creates request with url and auth`() {
        val request =
            AddServerRequestBuilder.build(
                name = "  weather-server  ",
                mode = AddServerMode.HTTP,
                url = "  https://api.weather.com/mcp  ",
                command = "ignored-cmd",
                args = "ignored-arg",
                auth = "header",
                bearerToken = "  secret-token  ",
            )
        assertEquals("weather-server", request.name)
        assertEquals("https://api.weather.com/mcp", request.url)
        assertNull(request.command)
        assertNull(request.args)
        assertEquals("header", request.auth)
        assertEquals("secret-token", request.bearerToken)
    }

    @Test
    fun `build HTTP mode with none auth nulls auth and bearerToken`() {
        val request =
            AddServerRequestBuilder.build(
                name = "public-server",
                mode = AddServerMode.HTTP,
                url = "https://example.com/mcp",
                command = "",
                args = "",
                auth = "none",
                bearerToken = "should-be-ignored",
            )
        assertEquals("public-server", request.name)
        assertEquals("https://example.com/mcp", request.url)
        assertNull(request.auth)
        assertNull(request.bearerToken)
    }

    @Test
    fun `build Stdio mode creates request with command and parsed args`() {
        val request =
            AddServerRequestBuilder.build(
                name = "local-tools",
                mode = AddServerMode.Stdio,
                url = "https://ignored.com",
                command = "  python3  ",
                args = "  -m   mcp_server   --debug  ",
                auth = "header",
                bearerToken = "token",
            )
        assertEquals("local-tools", request.name)
        assertNull(request.url)
        assertEquals("python3", request.command)
        assertEquals(listOf("-m", "mcp_server", "--debug"), request.args)
        assertNull(request.auth)
        assertNull(request.bearerToken)
    }

    @Test
    fun `build Stdio mode with empty args nulls args`() {
        val request =
            AddServerRequestBuilder.build(
                name = "npx-server",
                mode = AddServerMode.Stdio,
                url = "",
                command = "npx server",
                args = "    ",
                auth = "none",
                bearerToken = "",
            )
        assertEquals("npx server", request.command)
        assertNull(request.args)
    }

    @Test
    fun `build from McpServersUiState populates fields correctly`() {
        val state =
            McpServersUiState(
                addServerName = "state-server",
                addMode = AddServerMode.HTTP,
                addServerUrl = "https://state.com",
                addServerAuth = "none",
            )
        val request = AddServerRequestBuilder.build(state)
        assertEquals("state-server", request.name)
        assertEquals("https://state.com", request.url)
    }
}
