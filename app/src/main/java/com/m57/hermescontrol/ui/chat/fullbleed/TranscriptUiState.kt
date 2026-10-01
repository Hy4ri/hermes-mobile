package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatTimelineState
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.PendingSendState
import com.m57.hermescontrol.ui.chat.StreamingState
import com.m57.hermescontrol.ui.chat.VaultCodePromptUi
import com.m57.hermescontrol.ui.chat.VaultSaveLoginPromptUi
import com.m57.hermescontrol.ui.chat.VaultUnlockPromptUi
import com.m57.hermescontrol.ui.chat.messagesWithoutUnconfirmedReceipts

/** The transcript's resolved, read-only state; the list never receives the whole chat ViewModel. */
data class TranscriptUiState(
    val messages: List<ChatMessage>,
    val streamingState: StreamingState,
    val isAgentTyping: Boolean,
    val typingEffectEnabled: Boolean,
    val typingEffectDelayMs: Int,
    val messageStatsEnabled: Boolean,
    val showUserMessageTokens: Boolean,
    val showAssistantMessageTokens: Boolean,
    val showTokensPerSecond: Boolean,
    val maxToolCallsPerTurn: Int?,
    val isLoading: Boolean,
    val isLoadingOlder: Boolean,
    val hasOlderMessages: Boolean,
    val pagingSessionId: String?,
    val clarifyRequest: ClarifyUi?,
    val vaultUnlockPrompt: VaultUnlockPromptUi?,
    val vaultSaveLoginPrompt: VaultSaveLoginPromptUi?,
    val vaultCodePrompt: VaultCodePromptUi?,
    val savingAttachmentPath: String?,
    val openingAttachmentPath: String?,
    val isCompressing: Boolean,
    val compressionStatus: String?,
    val speakingMessageId: String?,
    val pendingSendStates: Map<String, PendingSendState> = emptyMap(),
) {
    companion object {
        /** Resolve historical versus live mode at the state boundary, not in the screen call. */
        fun resolve(
            chat: ChatUiState,
            timeline: ChatTimelineState,
            streaming: StreamingState,
            savingAttachmentPath: String?,
            speakingMessageId: String?,
        ): TranscriptUiState {
            val historical = timeline.isHistorical
            return TranscriptUiState(
                messages =
                    timeline.historyMessages ?: messagesWithoutUnconfirmedReceipts(chat.messages, chat.pendingSends),
                streamingState = if (historical) StreamingState() else streaming,
                isAgentTyping = !historical && chat.isAgentTyping,
                typingEffectEnabled = !historical && chat.typingEffectEnabled,
                typingEffectDelayMs = chat.typingEffectDelayMs,
                messageStatsEnabled = chat.messageStatsEnabled,
                showUserMessageTokens = chat.showUserMessageTokens,
                showAssistantMessageTokens = chat.showAssistantMessageTokens,
                showTokensPerSecond = chat.showTokensPerSecond,
                maxToolCallsPerTurn = chat.maxToolCallsPerTurn,
                isLoading = !historical && chat.isLoading,
                isLoadingOlder = !historical && chat.isLoadingOlder,
                hasOlderMessages = !historical && chat.hasOlderMessages,
                pagingSessionId =
                    chat.currentSessionId?.let { id ->
                        if (historical) "$id:history:${timeline.historyAnchorRowId}" else id
                    },
                clarifyRequest = chat.clarifyRequest.takeUnless { historical },
                vaultUnlockPrompt = chat.vaultUnlockPrompt.takeUnless { historical },
                vaultSaveLoginPrompt = chat.vaultSaveLoginPrompt.takeUnless { historical },
                vaultCodePrompt = chat.vaultCodePrompt.takeUnless { historical },
                savingAttachmentPath = savingAttachmentPath ?: chat.savingAttachmentPath,
                openingAttachmentPath = chat.openingAttachmentPath,
                isCompressing = !historical && chat.isCompressing,
                compressionStatus = chat.compressionStatus.takeUnless { historical },
                speakingMessageId = speakingMessageId,
                pendingSendStates =
                    if (historical) emptyMap() else chat.pendingSends.associate { it.id to it.state },
            )
        }
    }
}

/** All effects the transcript can request. The UI never reaches through to [ChatUiState]'s owner. */
class TranscriptActions(
    val onLoadOlder: () -> Unit,
    val onOpenAttachment: (Attachment) -> Unit,
    val onSaveAttachment: (Attachment) -> Unit,
    val onImageClick: (ImageViewerModel) -> Unit,
    val onRespondApproval: (String) -> Unit,
    val onRespondClarify: (String) -> Unit,
    val onRespondClarifyBatch: (Map<String, String>) -> Unit,
    val onDismissClarify: () -> Unit,
    val onRespondVaultUnlock: (String) -> Unit,
    val onDismissVaultUnlock: () -> Unit,
    val onRespondVaultSaveLogin: (String, String) -> Unit,
    val onDismissVaultSaveLogin: () -> Unit,
    val onRespondVaultCode: (String) -> Unit,
    val onDismissVaultCode: () -> Unit,
    val onToggleSpeak: (ChatMessage) -> Unit,
)
