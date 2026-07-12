package com.m57.hermescontrol.data.model

data class SessionTreeItem(
    val session: SessionInfo,
    val depth: Int,
    val branchStem: String?,
    val displayTitle: String,
)

fun flattenSessionTree(sessions: List<SessionInfo>): List<SessionTreeItem> {
    val ids = sessions.mapTo(mutableSetOf()) { it.id }
    val children = sessions.groupBy { it.parent_session_id?.takeIf(ids::contains) }
    val result = mutableListOf<SessionTreeItem>()
    val visited = mutableSetOf<String>()

    fun append(
        session: SessionInfo,
        depth: Int,
        branchStem: String?,
        siblingIndex: Int,
    ) {
        if (!visited.add(session.id)) return
        val isBranch = session.parent_session_id?.takeIf(ids::contains) != null
        val title =
            session.title?.takeIf(String::isNotBlank)
                ?: session.display_name?.takeIf(String::isNotBlank)
                ?: if (isBranch) {
                    "branch ${siblingIndex + 1}"
                } else {
                    session.preview?.takeIf(String::isNotBlank)?.take(80) ?: "Untitled"
                }
        result += SessionTreeItem(session, depth, branchStem, title)
        val descendants = children[session.id].orEmpty()
        descendants.forEachIndexed { index, child ->
            append(
                session = child,
                depth = depth + 1,
                branchStem = if (index == descendants.lastIndex) "└─" else "├─",
                siblingIndex = index,
            )
        }
    }

    children[null].orEmpty().forEachIndexed { index, root ->
        append(root, depth = 0, branchStem = null, siblingIndex = index)
    }
    sessions.filterNot { it.id in visited }.forEachIndexed { index, orphan ->
        append(orphan, depth = 0, branchStem = null, siblingIndex = index)
    }
    return result
}
