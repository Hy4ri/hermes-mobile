package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

class RpcMethod<P, R>(
    val name: String,
    val params: KSerializer<P>,
    val result: KSerializer<R>,
)

interface TypedRpcSender {
    fun <P> send(
        method: RpcMethod<P, *>,
        params: P,
        onSent: ((String) -> Unit)? = null,
    ): String
}

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

    val SESSION_INTERRUPT: RpcMethod<SessionInterruptParams, SessionInterruptResult> =
        RpcMethod(
            WsMethods.SESSION_INTERRUPT,
            SessionInterruptParams.serializer(),
            SessionInterruptResult.serializer(),
        )

    val SESSION_STEER: RpcMethod<SessionCorrectionParams, SessionCorrectionResult> =
        RpcMethod(
            WsMethods.SESSION_STEER,
            SessionCorrectionParams.serializer(),
            SessionCorrectionResult.serializer(),
        )

    val SESSION_REDIRECT: RpcMethod<SessionCorrectionParams, SessionCorrectionResult> =
        RpcMethod(
            WsMethods.SESSION_REDIRECT,
            SessionCorrectionParams.serializer(),
            SessionCorrectionResult.serializer(),
        )

    // Passthrough JsonElement results: callers keep their existing legacy parsers.
    val SESSION_LIST: RpcMethod<SessionListParams, JsonElement> =
        RpcMethod(
            WsMethods.SESSION_LIST,
            SessionListParams.serializer(),
            JsonElement.serializer(),
        )

    val SESSION_BRANCH: RpcMethod<SessionBranchParams, JsonElement> =
        RpcMethod(
            WsMethods.SESSION_BRANCH,
            SessionBranchParams.serializer(),
            JsonElement.serializer(),
        )

    val SESSION_BRANCH_WHOLE: RpcMethod<SessionBranchWholeParams, JsonElement> =
        RpcMethod(
            WsMethods.SESSION_BRANCH_WHOLE,
            SessionBranchWholeParams.serializer(),
            JsonElement.serializer(),
        )

    val SESSION_COMPRESS: RpcMethod<SessionCompressParams, JsonElement> =
        RpcMethod(
            WsMethods.SESSION_COMPRESS,
            SessionCompressParams.serializer(),
            JsonElement.serializer(),
        )

    val SESSION_CONTEXT_BREAKDOWN: RpcMethod<SessionIdParams, JsonElement> =
        RpcMethod(
            WsMethods.SESSION_CONTEXT_BREAKDOWN,
            SessionIdParams.serializer(),
            JsonElement.serializer(),
        )

    val SESSION_USAGE: RpcMethod<SessionIdParams, JsonElement> =
        RpcMethod(
            WsMethods.SESSION_USAGE,
            SessionIdParams.serializer(),
            JsonElement.serializer(),
        )

    val PROMPT_BTW: RpcMethod<PromptBtwParams, PromptBtwResult> =
        RpcMethod(
            WsMethods.PROMPT_BTW,
            PromptBtwParams.serializer(),
            PromptBtwResult.serializer(),
        )

    // Passthrough JsonElement results: callers keep their existing legacy parsers.
    val APPROVAL_PENDING: RpcMethod<ApprovalPendingParams, JsonElement> =
        RpcMethod(
            WsMethods.APPROVAL_PENDING,
            ApprovalPendingParams.serializer(),
            JsonElement.serializer(),
        )

    // Passthrough JsonElement results: callers keep their existing legacy parsers.
    val APPROVAL_RECEIVED: RpcMethod<ApprovalReceivedParams, JsonElement> =
        RpcMethod(
            WsMethods.APPROVAL_RECEIVED,
            ApprovalReceivedParams.serializer(),
            JsonElement.serializer(),
        )

    // Passthrough JsonElement results: callers keep their existing legacy parsers.
    val APPROVAL_RESPOND: RpcMethod<ApprovalRespondParams, JsonElement> =
        RpcMethod(
            WsMethods.APPROVAL_RESPOND,
            ApprovalRespondParams.serializer(),
            JsonElement.serializer(),
        )

    // Passthrough JsonElement results: callers keep their existing legacy parsers.
    val SESSION_ACTIVE_LIST: RpcMethod<SessionActiveListParams, JsonElement> =
        RpcMethod(
            WsMethods.SESSION_ACTIVE_LIST,
            SessionActiveListParams.serializer(),
            JsonElement.serializer(),
        )

    // Passthrough JsonElement results: callers keep their existing legacy parsers.
    val SUBSCRIPTION_STATE: RpcMethod<EmptyParams, JsonElement> =
        RpcMethod(
            WsMethods.SUBSCRIPTION_STATE,
            EmptyParams.serializer(),
            JsonElement.serializer(),
        )

    val SUBSCRIPTION_PREVIEW: RpcMethod<SubscriptionPreviewParams, JsonElement> =
        RpcMethod(
            WsMethods.SUBSCRIPTION_PREVIEW,
            SubscriptionPreviewParams.serializer(),
            JsonElement.serializer(),
        )

    val SUBSCRIPTION_CHANGE: RpcMethod<SubscriptionChangeParams, JsonElement> =
        RpcMethod(
            WsMethods.SUBSCRIPTION_CHANGE,
            SubscriptionChangeParams.serializer(),
            JsonElement.serializer(),
        )

    val SUBSCRIPTION_UPGRADE: RpcMethod<SubscriptionUpgradeParams, JsonElement> =
        RpcMethod(
            WsMethods.SUBSCRIPTION_UPGRADE,
            SubscriptionUpgradeParams.serializer(),
            JsonElement.serializer(),
        )

    val SUBSCRIPTION_RESUME: RpcMethod<EmptyParams, JsonElement> =
        RpcMethod(
            WsMethods.SUBSCRIPTION_RESUME,
            EmptyParams.serializer(),
            JsonElement.serializer(),
        )

    val USAGE_BARS: RpcMethod<EmptyParams, JsonElement> =
        RpcMethod(
            WsMethods.USAGE_BARS,
            EmptyParams.serializer(),
            JsonElement.serializer(),
        )

    val VAULT_LIST: RpcMethod<EmptyParams, JsonElement> =
        RpcMethod(
            WsMethods.VAULT_LIST,
            EmptyParams.serializer(),
            JsonElement.serializer(),
        )

    val VAULT_SOURCES: RpcMethod<EmptyParams, JsonElement> =
        RpcMethod(
            WsMethods.VAULT_SOURCES,
            EmptyParams.serializer(),
            JsonElement.serializer(),
        )

    val VAULT_SOURCE_SET: RpcMethod<VaultSourceSetParams, JsonElement> =
        RpcMethod(
            WsMethods.VAULT_SOURCE_SET,
            VaultSourceSetParams.serializer(),
            JsonElement.serializer(),
        )

    val VAULT_UNLOCK: RpcMethod<VaultUnlockParams, JsonElement> =
        RpcMethod(
            WsMethods.VAULT_UNLOCK,
            VaultUnlockParams.serializer(),
            JsonElement.serializer(),
        )

    val VAULT_LOCK: RpcMethod<VaultLockParams, JsonElement> =
        RpcMethod(
            WsMethods.VAULT_LOCK,
            VaultLockParams.serializer(),
            JsonElement.serializer(),
        )

    /** Every typed method. GatewayContractTest iterates this; future migrations append here. */
    val all: List<RpcMethod<*, *>> =
        listOf(
            SESSION_EVENTS_SINCE,
            SESSION_CREATE,
            SESSION_RESUME,
            PROMPT_SUBMIT,
            SESSION_INTERRUPT,
            SESSION_STEER,
            SESSION_REDIRECT,
            SESSION_LIST,
            SESSION_BRANCH,
            SESSION_BRANCH_WHOLE,
            SESSION_COMPRESS,
            SESSION_CONTEXT_BREAKDOWN,
            SESSION_USAGE,
            PROMPT_BTW,
            APPROVAL_PENDING,
            APPROVAL_RECEIVED,
            APPROVAL_RESPOND,
            SESSION_ACTIVE_LIST,
            SUBSCRIPTION_STATE,
            SUBSCRIPTION_PREVIEW,
            SUBSCRIPTION_CHANGE,
            SUBSCRIPTION_UPGRADE,
            SUBSCRIPTION_RESUME,
            USAGE_BARS,
            VAULT_LIST,
            VAULT_SOURCES,
            VAULT_SOURCE_SET,
            VAULT_UNLOCK,
            VAULT_LOCK,
        )
}
