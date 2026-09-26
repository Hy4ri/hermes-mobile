package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ConnectionEnvField
import com.m57.hermescontrol.data.model.ConnectionOperationSnapshot
import com.m57.hermescontrol.data.model.ConnectionOperationTarget
import com.m57.hermescontrol.data.model.ConnectionTargetAction
import com.m57.hermescontrol.data.model.ConnectionTargetKind
import com.m57.hermescontrol.data.model.ConnectionTargetState

/** Tolerant parser for backend-owned connector operation snapshots. */
object ConnectionOperationParser {
    fun parse(
        payload: Map<String, Any?>,
        sessionId: String? = null,
    ): ConnectionOperationSnapshot? {
        val opId = (payload["op_id"] as? String)?.trim() ?: return null
        val seq = payload["seq"].toLongOrNull() ?: return null
        val deadlineAt = payload["deadline_at"].toDoubleOrNull() ?: return null
        val targets =
            (payload["targets"] as? List<*>)
                ?.mapNotNull { parseTarget(it as? Map<*, *>) }
                ?: return null
        if (opId.isBlank() || seq < 0L || !deadlineAt.isFinite() || deadlineAt <= 0.0 || targets.isEmpty()) {
            return null
        }
        return ConnectionOperationSnapshot(
            sessionId = resolveSessionId(payload, sessionId),
            opId = opId,
            seq = seq,
            deadlineAt = deadlineAt,
            timeoutSeconds =
                payload["timeout_seconds"]
                    .toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 },
            toolCallId = payload["tool_call_id"] as? String,
            settled = payload["settled"] as? Boolean ?: false,
            settledBy = payload["settled_by"] as? String,
            targets = targets,
        )
    }

    /**
     * Session that owns the operation. Since hermes-agent v0.21.5 updates carry `owner` (#1281):
     * session-owned ones name their session; account-owned ones never bind to a chat.
     */
    private fun resolveSessionId(
        payload: Map<String, Any?>,
        envelopeSessionId: String?,
    ): String? {
        val owner = payload["owner"] as? Map<*, *>
        if (owner?.get("type") == "account") return null
        val ownerSessionId = (owner?.get("session_id") as? String).takeIf { owner?.get("type") == "session" }
        return listOf(envelopeSessionId, ownerSessionId, payload["session_id"] as? String)
            .firstOrNull { !it.isNullOrBlank() }
    }

    private fun parseTarget(raw: Map<*, *>?): ConnectionOperationTarget? {
        val map = raw ?: return null
        val name = (map["name"] as? String)?.trim() ?: return null
        if (name.isBlank()) return null
        return ConnectionOperationTarget(
            name = name,
            kind =
                enumValue(
                    map["kind"] as? String,
                    ConnectionTargetKind.values(),
                    ConnectionTargetKind.UNKNOWN,
                ),
            action =
                enumValue(
                    map["action"] as? String,
                    ConnectionTargetAction.values(),
                    ConnectionTargetAction.UNKNOWN,
                ),
            state =
                enumValue(
                    map["state"] as? String,
                    ConnectionTargetState.values(),
                    ConnectionTargetState.UNKNOWN,
                ),
            detail = map["detail"] as? String,
            instructions = map["instructions"] as? String,
            discoveryError = map["discovery_error"] as? String,
            connectUrl = map["connect_url"] as? String,
            connectionId = map["connection_id"] as? String,
            attempt = map["attempt"] as? String,
            requiredEnv =
                (map["required_env"] as? List<*>)
                    ?.mapNotNull { parseEnv(it as? Map<*, *>) }
                    ?: emptyList(),
            tools = (map["tools"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            hint = map["hint"] as? String,
        )
    }

    private fun parseEnv(raw: Map<*, *>?): ConnectionEnvField? {
        val map = raw ?: return null
        val name = (map["name"] as? String)?.trim() ?: return null
        if (name.isBlank()) return null
        val secret = map["secret"] as? Boolean ?: false
        return ConnectionEnvField(
            name = name,
            required = map["required"] as? Boolean ?: false,
            secret = secret,
            defaultValue = if (secret) null else map["default"] as? String,
            prompt = map["prompt"] as? String,
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(
        raw: String?,
        values: Array<T>,
        fallback: T,
    ): T = values.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback

    private fun Any?.toLongOrNull(): Long? =
        when (this) {
            is Number -> this.toLong().takeIf { this.toDouble() == it.toDouble() }
            is String -> this.toLongOrNull()
            else -> null
        }

    private fun Any?.toDoubleOrNull(): Double? =
        when (this) {
            is Number -> this.toDouble()
            is String -> this.toDoubleOrNull()
            else -> null
        }
}
