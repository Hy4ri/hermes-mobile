package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.serialization.KSerializer

class RpcMethod<P, R>(
    val name: String,
    val params: KSerializer<P>,
    val result: KSerializer<R>,
)

object RpcMethods {
    val SESSION_EVENTS_SINCE: RpcMethod<SessionEventsSinceParams, SessionEventsSinceResult> =
        RpcMethod(
            WsMethods.SESSION_EVENTS_SINCE,
            SessionEventsSinceParams.serializer(),
            SessionEventsSinceResult.serializer(),
        )

    val SESSION_CREATE: RpcMethod<SessionCreateParams, SessionCreateResult> =
        RpcMethod(
            WsMethods.SESSION_CREATE,
            SessionCreateParams.serializer(),
            SessionCreateResult.serializer(),
        )

    val SESSION_RESUME: RpcMethod<SessionResumeParams, SessionResumeResult> =
        RpcMethod(
            WsMethods.SESSION_RESUME,
            SessionResumeParams.serializer(),
            SessionResumeResult.serializer(),
        )

    val PROMPT_SUBMIT: RpcMethod<PromptSubmitParams, PromptSubmitResult> =
        RpcMethod(
            WsMethods.PROMPT_SUBMIT,
            PromptSubmitParams.serializer(),
            PromptSubmitResult.serializer(),
        )

    /** Every typed method. GatewayContractTest iterates this; future migrations append here. */
    val all: List<RpcMethod<*, *>> =
        listOf(
            SESSION_EVENTS_SINCE,
            SESSION_CREATE,
            SESSION_RESUME,
            PROMPT_SUBMIT,
        )
}
