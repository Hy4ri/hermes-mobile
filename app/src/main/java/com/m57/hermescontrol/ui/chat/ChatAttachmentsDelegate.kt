package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns pending-attachment state for [ChatViewModel], extracted to keep the
 * god-object focused on messaging/session/streaming concerns.
 *
 * Behavior is identical to the original inline implementation: the three
 * methods only mutate `_uiState.pendingAttachments`. Read sites (e.g. sendMessage)
 * continue to read `pendingAttachments` straight off the shared uiState flow.
 */
class ChatAttachmentsDelegate(
    private val uiState: MutableStateFlow<ChatUiState>,
) {
    fun addAttachment(
        uri: String,
        name: String,
        mimeType: String,
        size: Long,
    ) {
        uiState.update { state ->
            val attachment =
                Attachment(
                    uri = uri,
                    name = name,
                    mimeType = mimeType,
                    size = size,
                )
            state.copy(
                pendingAttachments = state.pendingAttachments + attachment,
            )
        }
    }

    fun removeAttachment(index: Int) {
        uiState.update { state ->
            state.copy(
                pendingAttachments =
                    state.pendingAttachments.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    },
            )
        }
    }

    fun clearAttachments() {
        uiState.update { it.copy(pendingAttachments = emptyList()) }
    }
}
