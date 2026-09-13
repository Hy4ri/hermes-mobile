package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ConnectorConnectItem
import com.m57.hermescontrol.data.model.ConnectorConnectResult
import com.m57.hermescontrol.data.model.ConnectorConnectStatus
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.model.ConnectorItem
import com.m57.hermescontrol.data.model.ConnectorListResult
import com.m57.hermescontrol.data.model.ConnectorStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorParserTest {
    @Test
    fun `parseListResult parses standard valid envelope`() {
        val payload =
            mapOf(
                "available" to true,
                "connectors" to
                    listOf(
                        mapOf(
                            "connector" to "linear",
                            "name" to "Linear",
                            "connected" to true,
                            "enabled" to true,
                            "connectionStatus" to "active",
                        ),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertTrue(success.available)
        assertEquals(1, success.connectors.size)
        val item = success.connectors[0]
        assertEquals("linear", item.connector)
        assertEquals("Linear", item.name)
        assertTrue(item.connected)
        assertTrue(item.enabled)
        assertEquals(ConnectorStatus.CONNECTED, item.status)
        assertTrue(item.isConnected)
    }

    @Test
    fun `parseListResult handles unavailable state`() {
        val payload =
            mapOf(
                "available" to false,
                "connectors" to emptyList<Map<String, Any>>(),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertFalse(success.available)
        assertTrue(success.connectors.isEmpty())
    }

    @Test
    fun `parseListResult handles empty connectors list`() {
        val payload =
            mapOf(
                "available" to true,
                "connectors" to emptyList<Map<String, Any>>(),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertTrue(success.available)
        assertTrue(success.connectors.isEmpty())
    }

    @Test
    fun `parseListResult deliberate boolean connected precedence over stale backend statuses`() {
        // When connected is true, status is ALWAYS CONNECTED even if backend reports expired or revoked
        val payloadExpired =
            mapOf(
                "connectors" to
                    listOf(
                        mapOf("connector" to "c1", "connected" to true, "connectionStatus" to "expired"),
                        mapOf("connector" to "c2", "connected" to true, "connectionStatus" to "revoked"),
                    ),
            )
        val resultExpired = ConnectorParser.parseListResult(payloadExpired) as ConnectorListResult.Success
        assertEquals(ConnectorStatus.CONNECTED, resultExpired.connectors[0].status)
        assertEquals(ConnectorStatus.CONNECTED, resultExpired.connectors[1].status)

        // When connected is false, status reflects expired or revoked, and stale "active" resolves to DISCONNECTED
        val payloadFalse =
            mapOf(
                "connectors" to
                    listOf(
                        mapOf("connector" to "c3", "connected" to false, "connectionStatus" to "expired"),
                        mapOf("connector" to "c4", "connected" to false, "connectionStatus" to "revoked"),
                        mapOf("connector" to "c5", "connected" to false, "connectionStatus" to "active"),
                        mapOf("connector" to "c6", "connected" to false, "connectionStatus" to "initiated"),
                        mapOf("connector" to "c7", "connected" to false, "connectionStatus" to "error"),
                    ),
            )
        val resultFalse = ConnectorParser.parseListResult(payloadFalse) as ConnectorListResult.Success
        assertEquals(ConnectorStatus.EXPIRED, resultFalse.connectors[0].status)
        assertEquals(ConnectorStatus.REVOKED, resultFalse.connectors[1].status)
        assertEquals(ConnectorStatus.DISCONNECTED, resultFalse.connectors[2].status)
        assertEquals(ConnectorStatus.INITIATED, resultFalse.connectors[3].status)
        assertEquals(ConnectorStatus.FAILED, resultFalse.connectors[4].status)
    }

    @Test
    fun `parseListResult supports legacy aliases`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        mapOf(
                            "slug" to "github",
                            "displayName" to "GitHub App",
                            "status" to "expired",
                            "auth_url" to "https://github.com/login/oauth/authorize",
                        ),
                        mapOf(
                            "id" to "jira",
                            "title" to "Jira Cloud",
                            "connection_status" to "revoked",
                            "connectUrl" to "https://auth.atlassian.com/oauth",
                        ),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertEquals(2, success.connectors.size)

        val c1 = success.connectors[0]
        assertEquals("github", c1.connector)
        assertEquals("GitHub App", c1.name)
        assertEquals(ConnectorStatus.EXPIRED, c1.status)
        assertEquals("https://github.com/login/oauth/authorize", c1.safeConnectUrl)

        val c2 = success.connectors[1]
        assertEquals("jira", c2.connector)
        assertEquals("Jira Cloud", c2.name)
        assertEquals(ConnectorStatus.REVOKED, c2.status)
        assertEquals("https://auth.atlassian.com/oauth", c2.safeConnectUrl)
    }

    @Test
    fun `parseListResult safely handles wrong optional field types without crash`() {
        val payload =
            mapOf(
                "available" to "true",
                "connectors" to
                    listOf(
                        mapOf(
                            "connector" to "slack",
                            "name" to 12345, // Number instead of string
                            "connected" to "true", // String instead of boolean
                            "enabled" to "false", // String instead of boolean
                        ),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        val item = success.connectors[0]
        assertEquals("slack", item.connector)
        assertEquals("12345", item.name)
        assertTrue(item.connected)
        assertFalse(item.enabled)
    }

    @Test
    fun `parseListResult maps unknown status to UNKNOWN`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        mapOf(
                            "connector" to "notion",
                            "connected" to false,
                            "connectionStatus" to "pending_approval_future_state",
                        ),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload) as ConnectorListResult.Success
        assertEquals(ConnectorStatus.UNKNOWN, result.connectors[0].status)
    }

    @Test
    fun `parseListResult fails on malformed required envelopes without fabricating empty success`() {
        // Missing "connectors" key
        val missingConnectors = mapOf("available" to true)
        val r1 = ConnectorParser.parseListResult(missingConnectors)
        assertTrue(r1 is ConnectorListResult.Error)
        assertTrue((r1 as ConnectorListResult.Error).error is ConnectorError.MalformedEnvelope)

        // "connectors" is a string instead of array
        val stringConnectors = mapOf("connectors" to "invalid")
        val r2 = ConnectorParser.parseListResult(stringConnectors)
        assertTrue(r2 is ConnectorListResult.Error)
        assertTrue((r2 as ConnectorListResult.Error).error is ConnectorError.MalformedEnvelope)

        // Raw array at root instead of object
        val rawList = listOf("c1", "c2")
        val r3 = ConnectorParser.parseListResult(rawList)
        assertTrue(r3 is ConnectorListResult.Error)
        assertTrue((r3 as ConnectorListResult.Error).error is ConnectorError.MalformedEnvelope)

        // Primitive string at root
        val r4 = ConnectorParser.parseListResult("some random string")
        assertTrue(r4 is ConnectorListResult.Error)
        assertTrue((r4 as ConnectorListResult.Error).error is ConnectorError.MalformedEnvelope)

        // Null root
        val r5 = ConnectorParser.parseListResult(null)
        assertTrue(r5 is ConnectorListResult.Error)
        assertTrue((r5 as ConnectorListResult.Error).error is ConnectorError.MalformedEnvelope)
    }

    @Test
    fun `parseConnectResult parses standard connect initiated response`() {
        val payload =
            mapOf(
                "results" to
                    listOf(
                        mapOf(
                            "connector" to "linear",
                            "status" to "initiated",
                            "connect_url" to "https://linear.app/oauth/authorize?client_id=123",
                            "instruction" to "Arbitrary backend instruction that must not be exposed as UI text",
                        ),
                    ),
                "summary" to
                    mapOf(
                        "total" to 1,
                        "active" to 0,
                        "initiated" to 1,
                        "failed" to 0,
                    ),
            )

        val result = ConnectorParser.parseConnectResult(payload)
        assertTrue(result is ConnectorConnectResult.Success)
        val success = result as ConnectorConnectResult.Success
        assertEquals(1, success.results.size)
        val item = success.results[0]
        assertEquals("linear", item.connector)
        assertEquals(ConnectorConnectStatus.INITIATED, item.status)
        assertTrue(item.isConnectUrlValid)
        assertEquals("https://linear.app/oauth/authorize?client_id=123", item.safeConnectUrl)
        assertEquals(1, success.summary.total)
        assertEquals(1, success.summary.initiated)
        assertEquals(0, success.summary.active)
    }

    @Test
    fun `parseConnectResult parses connect already active response`() {
        val payload =
            mapOf(
                "results" to
                    listOf(
                        mapOf(
                            "connector" to "linear",
                            "status" to "active",
                        ),
                    ),
                "summary" to
                    mapOf(
                        "total" to 1,
                        "active" to 1,
                        "initiated" to 0,
                        "failed" to 0,
                    ),
            )

        val result = ConnectorParser.parseConnectResult(payload)
        assertTrue(result is ConnectorConnectResult.Success)
        val success = result as ConnectorConnectResult.Success
        assertEquals(1, success.results.size)
        val item = success.results[0]
        assertEquals("linear", item.connector)
        assertEquals(ConnectorConnectStatus.ACTIVE, item.status)
        assertTrue(item.status.isActive)
        assertNull(item.safeConnectUrl)
    }

    @Test
    fun `parseConnectResult supports legacy aliases and unknown status`() {
        val payload =
            mapOf(
                "results" to
                    listOf(
                        mapOf(
                            "slug" to "github",
                            "connectionStatus" to "failed",
                            "auth_url" to "https://github.com/login",
                        ),
                        mapOf(
                            "connector" to "notion",
                            "status" to "custom_unknown_status",
                        ),
                    ),
                "summary" to
                    mapOf(
                        "total" to "2", // String count coerced safely
                        "failed" to "1",
                    ),
            )

        val result = ConnectorParser.parseConnectResult(payload) as ConnectorConnectResult.Success
        assertEquals(2, result.results.size)
        assertEquals("github", result.results[0].connector)
        assertEquals(ConnectorConnectStatus.FAILED, result.results[0].status)
        assertEquals("https://github.com/login", result.results[0].safeConnectUrl)

        assertEquals("notion", result.results[1].connector)
        assertEquals(ConnectorConnectStatus.UNKNOWN, result.results[1].status)

        assertEquals(2, result.summary.total)
        assertEquals(1, result.summary.failed)
        assertEquals(0, result.summary.active)
    }

    @Test
    fun `parseConnectResult rejects insecure or invalid auth URLs`() {
        val payload =
            mapOf(
                "results" to
                    listOf(
                        mapOf(
                            "connector" to "insecure_http",
                            "status" to "initiated",
                            "connect_url" to "http://example.com/oauth",
                        ),
                        mapOf(
                            "connector" to "js_proto",
                            "status" to "initiated",
                            "connect_url" to "javascript:alert(1)",
                        ),
                        mapOf(
                            "connector" to "user_creds",
                            "status" to "initiated",
                            "connect_url" to "https://user:pass@evil.com/",
                        ),
                    ),
                "summary" to
                    mapOf(
                        "total" to 3,
                        "initiated" to 3,
                    ),
            )

        val result = ConnectorParser.parseConnectResult(payload) as ConnectorConnectResult.Success
        assertEquals(3, result.results.size)
        for (item in result.results) {
            assertFalse(item.isConnectUrlValid)
            assertNull(item.safeConnectUrl)
        }
    }

    @Test
    fun `parseConnectResult fails on malformed required envelope without fabricating success`() {
        // Missing results
        val r1 = ConnectorParser.parseConnectResult(mapOf("summary" to mapOf("total" to 0)))
        assertTrue(r1 is ConnectorConnectResult.Error)
        assertTrue((r1 as ConnectorConnectResult.Error).error is ConnectorError.MalformedEnvelope)

        // Results is a string
        val r2 = ConnectorParser.parseConnectResult(mapOf("results" to "not a list"))
        assertTrue(r2 is ConnectorConnectResult.Error)
        assertTrue((r2 as ConnectorConnectResult.Error).error is ConnectorError.MalformedEnvelope)

        // Primitive string
        val r3 = ConnectorParser.parseConnectResult("random text")
        assertTrue(r3 is ConnectorConnectResult.Error)
    }

    @Test
    fun `toString redacts auth URLs on ConnectorItem and ConnectorConnectItem`() {
        val sensitiveUrl = "https://example.com/oauth?client_secret=super_secret_token_12345"
        val item =
            ConnectorItem(
                connector = "linear",
                name = "Linear",
                connectUrl = sensitiveUrl,
            )
        assertFalse(item.toString().contains("super_secret_token_12345"))
        assertTrue(item.toString().contains("[REDACTED]"))

        val connectItem =
            ConnectorConnectItem(
                connector = "linear",
                status = ConnectorConnectStatus.INITIATED,
                connectUrl = sensitiveUrl,
            )
        assertFalse(connectItem.toString().contains("super_secret_token_12345"))
        assertTrue(connectItem.toString().contains("[REDACTED]"))
    }

    @Test
    fun `mapRpcError correctly maps backend codes and reasons`() {
        // 4000 INVALID_PARAMS
        val e4000 =
            ConnectorParser.mapRpcError(
                code = 4000,
                message = "session_id required",
                data = JsonObject(mapOf("reason" to JsonPrimitive("INVALID_PARAMS"))),
            )
        assertTrue(e4000 is ConnectorError.InvalidParams)

        // 4001 NOT_OWNER
        val e4001 =
            ConnectorParser.mapRpcError(
                code = 4001,
                message = "session not owned",
                data = JsonObject(mapOf("reason" to JsonPrimitive("NOT_OWNER"))),
            )
        assertTrue(e4001 is ConnectorError.NotOwner)

        // 4031 CONNECTORS_UNAVAILABLE
        val e4031 =
            ConnectorParser.mapRpcError(
                code = 4031,
                message = "connectors unavailable",
                data = JsonObject(mapOf("reason" to JsonPrimitive("CONNECTORS_UNAVAILABLE"))),
            )
        assertTrue(e4031 is ConnectorError.Unavailable)

        // 5033 UNSUPPORTED_RUNTIME
        val e5033 =
            ConnectorParser.mapRpcError(
                code = 5033,
                message = "compute host required",
                data = JsonObject(mapOf("reason" to JsonPrimitive("UNSUPPORTED_RUNTIME"))),
            )
        assertTrue(e5033 is ConnectorError.UnsupportedRuntime)

        // 5034 INVALID_CONNECTOR_RESPONSE
        val e5034a =
            ConnectorParser.mapRpcError(
                code = 5034,
                message = "invalid response",
                data = JsonObject(mapOf("reason" to JsonPrimitive("INVALID_CONNECTOR_RESPONSE"))),
            )
        assertTrue(e5034a is ConnectorError.InvalidResponse)

        // 5034 CONNECTOR_REQUEST_FAILED
        val e5034b =
            ConnectorParser.mapRpcError(
                code = 5034,
                message = "request failed",
                data = JsonObject(mapOf("reason" to JsonPrimitive("CONNECTOR_REQUEST_FAILED"))),
            )
        assertTrue(e5034b is ConnectorError.RequestFailed)

        // -32601 Method not found
        val e32601 =
            ConnectorParser.mapRpcError(
                code = -32601,
                message = "Method not found",
                data = null,
            )
        assertTrue(e32601 is ConnectorError.UnsupportedBackend)

        // Other code
        val eOther =
            ConnectorParser.mapRpcError(
                code = 9999,
                message = "Custom failure",
                data = null,
            )
        assertTrue(eOther is ConnectorError.Other)
        assertEquals(9999, (eOther as ConnectorError.Other).code)
    }

    @Test
    fun `mapRpcError never leaks raw backend messages or secrets into typed error or toString`() {
        val secretApiKey = "sk-live-secret-super-sensitive-api-key-987654321"
        val secretDbPass = "postgres://user:super_secret_db_pass@10.0.0.1:5432/db"

        // Known error code with secret in message
        val e4000 =
            ConnectorParser.mapRpcError(
                code = 4000,
                message = "Invalid auth header: $secretApiKey",
                data = JsonObject(mapOf("reason" to JsonPrimitive("INVALID_PARAMS"))),
            )
        assertFalse("Error message must not leak secret", e4000.message.contains(secretApiKey))
        assertFalse("Error toString must not leak secret", e4000.toString().contains(secretApiKey))
        assertEquals("Invalid connector parameters.", e4000.message)

        // Unknown error code with secret in message
        val eUnknown =
            ConnectorParser.mapRpcError(
                code = 8888,
                message = "Internal connection failed to $secretDbPass",
                data = null,
            )
        assertFalse("Unknown error message must not leak secret", eUnknown.message.contains(secretDbPass))
        assertFalse("Unknown error toString must not leak secret", eUnknown.toString().contains(secretDbPass))
        assertEquals("Connector operation failed.", eUnknown.message)
    }

    @Test
    fun `parseConnectorItem strictly rejects non-string identifiers and invalid slug formats`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        mapOf("connector" to true, "name" to "Boolean Slug"),
                        mapOf("id" to 12345, "name" to "Number ID"),
                        mapOf("slug" to "INVALID UPPERCASE", "name" to "Uppercase"),
                        mapOf("connector" to "-leading-hyphen", "name" to "Leading Hyphen"),
                        mapOf("connector" to "slug with spaces", "name" to "Spaces"),
                        mapOf("connector" to "valid_slug-1", "name" to "Valid Item"),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertEquals(1, success.connectors.size)
        assertEquals("valid_slug-1", success.connectors[0].connector)
        assertEquals("Valid Item", success.connectors[0].name)
    }

    @Test
    fun `parseListResult deduplicates items by slug to prevent duplicate keys in lazy lists`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        mapOf("connector" to "github", "name" to "First GitHub"),
                        mapOf("connector" to "github", "name" to "Duplicate GitHub"),
                        mapOf("connector" to "slack", "name" to "Slack"),
                        mapOf("connector" to "slack", "name" to "Duplicate Slack"),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertEquals(2, success.connectors.size)
        assertEquals("github", success.connectors[0].connector)
        assertEquals("First GitHub", success.connectors[0].name)
        assertEquals("slack", success.connectors[1].connector)
        assertEquals("Slack", success.connectors[1].name)
    }

    @Test
    fun `parseListResult fails with MalformedEnvelope when nonempty connectors array contains only invalid items`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        "not an object",
                        12345,
                        mapOf("connector" to true),
                        mapOf("slug" to "INVALID SLUG"),
                        mapOf("bogus" to "no identifier"),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Error)
        assertTrue((result as ConnectorListResult.Error).error is ConnectorError.MalformedEnvelope)
    }

    @Test
    fun `parseListResult tolerates mixed junk items when valid items exist`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        "random junk string",
                        mapOf("connector" to "linear", "name" to "Linear"),
                        12345,
                        mapOf("connector" to true),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertEquals(1, success.connectors.size)
        assertEquals("linear", success.connectors[0].connector)
    }

    @Test
    fun `parseConnectResult fails when results is empty or all items invalid`() {
        // Empty results array
        val payloadEmpty =
            mapOf(
                "results" to emptyList<Map<String, Any>>(),
                "summary" to mapOf("total" to 0),
            )
        val rEmpty = ConnectorParser.parseConnectResult(payloadEmpty)
        assertTrue(rEmpty is ConnectorConnectResult.Error)
        assertTrue((rEmpty as ConnectorConnectResult.Error).error is ConnectorError.InvalidResponse)

        // All invalid items
        val payloadInvalid =
            mapOf(
                "results" to
                    listOf(
                        mapOf("connector" to true),
                        mapOf("id" to 12345),
                        mapOf("connector" to "INVALID UPPER"),
                    ),
                "summary" to mapOf("total" to 3),
            )
        val rInvalid = ConnectorParser.parseConnectResult(payloadInvalid)
        assertTrue(rInvalid is ConnectorConnectResult.Error)
        assertTrue((rInvalid as ConnectorConnectResult.Error).error is ConnectorError.InvalidResponse)
    }

    @Test
    fun `parseConnectResult fails when summary object is missing or non-object in standard envelope`() {
        // Missing summary
        val payloadMissingSummary =
            mapOf(
                "results" to
                    listOf(
                        mapOf("connector" to "linear", "status" to "active"),
                    ),
            )
        val r1 = ConnectorParser.parseConnectResult(payloadMissingSummary)
        assertTrue(r1 is ConnectorConnectResult.Error)
        assertTrue((r1 as ConnectorConnectResult.Error).error is ConnectorError.MalformedEnvelope)

        // Non-object summary (string)
        val payloadStringSummary =
            mapOf(
                "results" to
                    listOf(
                        mapOf("connector" to "linear", "status" to "active"),
                    ),
                "summary" to "invalid_string_summary",
            )
        val r2 = ConnectorParser.parseConnectResult(payloadStringSummary)
        assertTrue(r2 is ConnectorConnectResult.Error)
        assertTrue((r2 as ConnectorConnectResult.Error).error is ConnectorError.MalformedEnvelope)
    }

    @Test
    fun `parseConnectResult tolerates purposeful legacy bare array form and derives summary`() {
        val legacyPayload =
            listOf(
                mapOf("connector" to "linear", "status" to "active"),
                mapOf("connector" to "github", "status" to "initiated", "connect_url" to "https://github.com/login"),
            )

        val result = ConnectorParser.parseConnectResult(legacyPayload)
        assertTrue(result is ConnectorConnectResult.Success)
        val success = result as ConnectorConnectResult.Success
        assertEquals(2, success.results.size)
        assertEquals("linear", success.results[0].connector)
        assertEquals(ConnectorConnectStatus.ACTIVE, success.results[0].status)
        assertEquals("github", success.results[1].connector)
        assertEquals(ConnectorConnectStatus.INITIATED, success.results[1].status)

        // Summary should be synthesized
        assertEquals(2, success.summary.total)
        assertEquals(1, success.summary.active)
        assertEquals(1, success.summary.initiated)
        assertEquals(0, success.summary.failed)
    }

    @Test
    fun `legacy connected consistency - status authorized yields effective connected when boolean is missing`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        // Missing "connected" boolean, status "authorized"
                        mapOf("connector" to "c1", "connectionStatus" to "authorized"),
                        // Missing "connected" boolean, status "active"
                        mapOf("connector" to "c2", "connectionStatus" to "active"),
                        // Missing "connected" boolean, status "connected"
                        mapOf("connector" to "c3", "connectionStatus" to "connected"),
                        // Missing "connected" boolean, status "expired"
                        mapOf("connector" to "c4", "connectionStatus" to "expired"),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        assertEquals(4, success.connectors.size)

        val c1 = success.connectors[0]
        assertEquals(ConnectorStatus.CONNECTED, c1.status)
        assertTrue(c1.connected)
        assertTrue(c1.isConnected)

        val c2 = success.connectors[1]
        assertEquals(ConnectorStatus.CONNECTED, c2.status)
        assertTrue(c2.connected)
        assertTrue(c2.isConnected)

        val c3 = success.connectors[2]
        assertEquals(ConnectorStatus.CONNECTED, c3.status)
        assertTrue(c3.connected)
        assertTrue(c3.isConnected)

        val c4 = success.connectors[3]
        assertEquals(ConnectorStatus.EXPIRED, c4.status)
        assertFalse(c4.connected)
        assertFalse(c4.isConnected)
    }

    @Test
    fun `explicit connected false is authoritative and not overridden by status active or authorized`() {
        val payload =
            mapOf(
                "connectors" to
                    listOf(
                        mapOf("connector" to "c1", "connected" to false, "connectionStatus" to "active"),
                        mapOf("connector" to "c2", "connected" to false, "connectionStatus" to "authorized"),
                        mapOf("connector" to "c3", "connected" to false, "connectionStatus" to "connected"),
                    ),
            )

        val result = ConnectorParser.parseListResult(payload)
        assertTrue(result is ConnectorListResult.Success)
        val success = result as ConnectorListResult.Success
        for (item in success.connectors) {
            assertFalse(item.connected)
            assertFalse(item.isConnected)
            assertEquals(ConnectorStatus.DISCONNECTED, item.status)
        }
    }
}
