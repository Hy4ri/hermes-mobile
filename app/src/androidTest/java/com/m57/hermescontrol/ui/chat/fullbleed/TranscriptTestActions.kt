package com.m57.hermescontrol.ui.chat.fullbleed

/** No-op actions for transcript layout tests; callbacks are irrelevant to their assertions. */
internal fun testTranscriptActions() =
    TranscriptActions(
        onLoadOlder = {},
        onOpenAttachment = {},
        onSaveAttachment = {},
        onImageClick = {},
        onRespondApproval = { _, _ -> },
        onRespondClarify = {},
        onRespondClarifyBatch = {},
        onDismissClarify = {},
        onRespondVaultUnlock = {},
        onDismissVaultUnlock = {},
        onRespondVaultSaveLogin = { _, _ -> },
        onDismissVaultSaveLogin = {},
        onRespondVaultCode = {},
        onDismissVaultCode = {},
        onToggleSpeak = {},
    )
