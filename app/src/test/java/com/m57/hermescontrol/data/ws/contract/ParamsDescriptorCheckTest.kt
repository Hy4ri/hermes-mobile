package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.ws.GatewayContract
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamsDescriptorCheckTest {
    @Serializable
    private data class ValidParams(
        @SerialName("session_id") val sessionId: String,
        @SerialName("last_seen") val lastSeen: Int? = null,
    )

    @Serializable
    private data class ExtraFieldParams(
        @SerialName("session_id") val sessionId: String,
        @SerialName("since_seq") val sinceSeq: Int? = null,
    )

    @Serializable
    private data class MissingRequiredParams(
        @SerialName("last_seen") val lastSeen: Int? = null,
    )

    @Serializable
    private data class DefaultOnRequiredParams(
        @SerialName("session_id") val sessionId: String = "default_id",
    )

    @Serializable
    private data class NullableRequiredParams(
        @SerialName("session_id") val sessionId: String?,
    )

    @Serializable
    private data class DeliverMissingProfileParams(
        val message: String,
    )

    @Test
    fun correctClassPasses() {
        val problems =
            GatewayContract.paramsDescriptorProblems(
                "session.events.since",
                ValidParams.serializer().descriptor,
            )
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun extraFieldIsRejected() {
        val problems =
            GatewayContract.paramsDescriptorProblems(
                "session.events.since",
                ExtraFieldParams.serializer().descriptor,
            )
        assertEquals(1, problems.count { "unknown param 'since_seq'" in it })
    }

    @Test
    fun missingRequiredFieldIsRejected() {
        val problems =
            GatewayContract.paramsDescriptorProblems(
                "session.events.since",
                MissingRequiredParams.serializer().descriptor,
            )
        assertEquals(1, problems.count { "missing required param 'session_id'" in it })
    }

    @Test
    fun requiredFieldWithDefaultValueIsRejected() {
        val problems =
            GatewayContract.paramsDescriptorProblems(
                "session.events.since",
                DefaultOnRequiredParams.serializer().descriptor,
            )
        assertEquals(1, problems.count { "required param 'session_id' must not be optional" in it })
    }

    @Test
    fun requiredFieldTypedNullableIsRejected() {
        val problems =
            GatewayContract.paramsDescriptorProblems(
                "session.events.since",
                NullableRequiredParams.serializer().descriptor,
            )
        assertEquals(
            1,
            problems.count { "required param 'session_id' must not be nullable unless schema allows null" in it },
        )
    }

    @Test
    fun omittingProfilePassesEvenWhenRequiredBySchema() {
        // bot_relay.deliver has required: ["profile", "message"]
        val problems =
            GatewayContract.paramsDescriptorProblems(
                "bot_relay.deliver",
                DeliverMissingProfileParams.serializer().descriptor,
            )
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
