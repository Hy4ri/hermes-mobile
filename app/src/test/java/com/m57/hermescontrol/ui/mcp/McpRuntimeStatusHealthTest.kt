package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.model.McpServerTestResponse
import com.m57.hermescontrol.data.ws.McpRuntimeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance tests for the #419 runtime-status resolution rules.
 */
class McpRuntimeStatusHealthTest {
    private fun row(
        name: String,
        status: String,
        connected: Boolean = false,
        needsAuth: Boolean = false,
        disabled: Boolean = false,
    ) = McpRuntimeStatus(
        name = name,
        transport = "stdio",
        tools = 0,
        connected = connected,
        disabled = disabled,
        status = status,
        source = "config",
        plugin = null,
        needsAuth = needsAuth,
    )

    @Test
    fun `needs_auth without error text yields sign-in state`() {
        assertEquals(
            McpHealthStatus.AUTH_REQUIRED,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = null,
                runtime = row("docs", "failed", needsAuth = true),
                serverStatus = null,
                serverError = null,
            ),
        )
    }

    @Test
    fun `omitted needs_auth preserves the legacy config-only behavior`() {
        // No row at all: a legacy `server.error` hint still resolves as before.
        assertEquals(
            McpHealthStatus.AUTH_REQUIRED,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = null,
                runtime = null,
                serverStatus = null,
                serverError = "OAuth token expired",
            ),
        )
        assertEquals(
            McpHealthStatus.HEALTHY,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = McpServerTestResponse(ok = true),
                runtime = null,
                serverStatus = null,
                serverError = null,
            ),
        )
    }

    @Test
    fun `stdlib configured and lazy rows are never flagged as auth or error`() {
        listOf("configured", "lazy").forEach { status ->
            val result =
                McpHealthStatus.resolveForServer(
                    isTesting = false,
                    testResult = null,
                    runtime = row("local", status),
                    serverStatus = null,
                    serverError = null,
                )
            assertEquals(McpHealthStatus.NOT_CONNECTED, result)
        }
    }

    @Test
    fun `connected row is healthy even without a probe result`() {
        assertEquals(
            McpHealthStatus.HEALTHY,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = null,
                runtime = row("docs", "connected", connected = true),
                serverStatus = null,
                serverError = null,
            ),
        )
    }

    @Test
    fun `connecting disabled and failed rows stay distinct`() {
        assertEquals(
            McpHealthStatus.CONNECTING,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = null,
                runtime = row("docs", "connecting"),
                serverStatus = null,
                serverError = null,
            ),
        )
        assertEquals(
            McpHealthStatus.DISCONNECTED,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = null,
                runtime = row("docs", "disabled", disabled = true),
                serverStatus = null,
                serverError = null,
            ),
        )
        assertEquals(
            McpHealthStatus.ERROR,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = null,
                runtime = row("docs", "failed"),
                serverStatus = null,
                serverError = null,
            ),
        )
    }

    @Test
    fun `in-flight test outranks runtime state`() {
        assertEquals(
            McpHealthStatus.TESTING,
            McpHealthStatus.resolveForServer(
                isTesting = true,
                testResult = null,
                runtime = row("docs", "connected", connected = true),
                serverStatus = null,
                serverError = null,
            ),
        )
    }
}
