package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.content.SharedPreferences
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.BusySendMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The receipt stays on device until REST confirms a user row; a gateway queue is only volatile. */
@Serializable
data class PendingSend(
    val id: String,
    val scope: String,
    val sessionId: String,
    val text: String,
    val attachments: List<Attachment> = emptyList(),
    val mode: BusySendMode,
    val state: PendingSendState = PendingSendState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

@Serializable
enum class PendingSendState {
    QUEUED,
    PARKED,
    SENDING,
    ACCEPTED,
    UNKNOWN,
    REJECTED,
}

/**
 * Reconcile only identities that the transcript merge already mapped to a durable REST row.
 * Content matching belongs to [matchTranscriptMessages], which normalizes gateway wrappers and
 * consumes duplicate occurrences once; repeating that matching against receipts can acknowledge
 * more sends than the history page actually contains.
 */
internal fun pendingSendIdsConfirmedByDurableAliases(
    confirmedAliases: List<ChatMessage>,
    pending: List<PendingSend>,
): Set<String> {
    val durableUserAliasIds =
        confirmedAliases
            .asSequence()
            .filter { it.role == MessageRole.USER && it.canonicalRestId != null }
            .map { it.id }
            .toSet()
    return pending
        .asSequence()
        .filter {
            it.state in
                setOf(PendingSendState.SENDING, PendingSendState.ACCEPTED, PendingSendState.UNKNOWN)
        }.map { it.id }
        .filter { it in durableUserAliasIds }
        .toSet()
}

/** Synchronous writes keep the queue recoverable when Android kills the process just after a tap. */
class ChatSendStore(
    private val prefs: SharedPreferences? = null,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("chat_send_outbox", Context.MODE_PRIVATE),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var rows: List<PendingSend> =
        prefs?.getString("rows", null)?.let { raw ->
            runCatching { json.decodeFromString<List<PendingSend>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()

    init {
        // A request may have reached the gateway before process death. Never replay it automatically.
        replace(
            rows.map {
                if (it.state == PendingSendState.SENDING || it.state == PendingSendState.ACCEPTED) {
                    it.copy(state = PendingSendState.UNKNOWN)
                } else {
                    it
                }
            },
        )
    }

    @Synchronized
    fun all(): List<PendingSend> = rows

    @Synchronized
    fun put(row: PendingSend) {
        replace(rows.filterNot { it.id == row.id } + row)
    }

    @Synchronized
    fun remove(id: String) {
        replace(rows.filterNot { it.id == id })
    }

    @Synchronized
    fun update(
        id: String,
        transform: (PendingSend) -> PendingSend,
    ) {
        replace(rows.map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun promote(id: String) {
        val row = rows.firstOrNull { it.id == id } ?: return
        replace(listOf(row.copy(state = PendingSendState.QUEUED)) + rows.filterNot { it.id == id })
    }

    @Synchronized
    fun park(
        scope: String,
        sessionId: String,
    ) {
        replace(
            rows.map {
                if (it.scope == scope && it.sessionId == sessionId && it.state == PendingSendState.QUEUED) {
                    it.copy(state = PendingSendState.PARKED)
                } else {
                    it
                }
            },
        )
    }

    private fun replace(next: List<PendingSend>) {
        if (prefs != null && !prefs.edit().putString("rows", json.encodeToString(next)).commit()) {
            error("Could not save chat send queue")
        }
        rows = next
    }
}
