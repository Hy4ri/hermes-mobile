package com.m57.hermescontrol.data.model

data class SessionListResponse(
    val sessions: List<SessionInfo>,
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

data class SessionInfo(
    val id: String,
    val title: String?,
    val created_at: String?,
    val message_count: Int?,
    val status: String?,
    val preview: String? = null,
    val started_at: Long? = null,
) {
    fun toActivityItem(): ActivityItem? {
        val ts = started_at ?: return null
        if (message_count == null || message_count <= 0) return null
        return ActivityItem(
            id = id,
            jobName = title ?: id.take(40),
            preview = preview?.take(80),
            status = if (status == "failed" || status == "error") "failed" else "sent",
            formattedTime = formatRelativeTime(ts),
            messageCount = message_count,
            timestamp = ts,
        )
    }
}

private fun formatRelativeTime(epochSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - epochSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}
