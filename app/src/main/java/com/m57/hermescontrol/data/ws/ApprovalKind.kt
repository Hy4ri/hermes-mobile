package com.m57.hermescontrol.data.ws

/**
 * Approval request kind (`ApprovalRequestParams.kind`). Absent means [COMMAND]:
 * older gateways never send it. `access` and `network` come from a sandbox
 * plugin asking about a folder or a website.
 */
enum class ApprovalKind(
    val wire: String,
) {
    COMMAND("command"),
    ACCESS("access"),
    NETWORK("network"),
    ;

    /** Folder and site cards outlive their turn: the sandbox still waits for the answer. */
    val isSandbox: Boolean get() = this != COMMAND
}

/** Kind-specific fields of an approval request, already validated. */
internal data class ApprovalKindFields(
    val kind: ApprovalKind,
    val path: String?,
    /** `read` or `read_write`. */
    val access: String?,
    val host: String?,
    val label: String?,
    val risk: String?,
)

/**
 * Read the kind fields of an approval payload (live event, server request, or
 * `approval.pending` entry). Returns null when the request must be refused: an
 * unknown kind, a folder request without a path, or a site request without a
 * host (desktop `eafc0baff0`). Callers then show no card and send no ack.
 */
internal fun parseApprovalKindFields(map: Map<*, *>): ApprovalKindFields? {
    val rawKind = map["kind"]
    val kind =
        when (rawKind) {
            null -> ApprovalKind.COMMAND
            is String -> ApprovalKind.entries.firstOrNull { it.wire == rawKind } ?: return null
            else -> return null
        }

    fun text(key: String): String? = (map[key] as? String)?.takeIf { it.isNotBlank() }
    val path = text("path")
    val host = text("host")
    if (kind == ApprovalKind.ACCESS && path == null) return null
    if (kind == ApprovalKind.NETWORK && host == null) return null
    return ApprovalKindFields(
        kind = kind,
        path = path,
        access = text("access"),
        host = host,
        label = text("label"),
        risk = text("risk"),
    )
}
