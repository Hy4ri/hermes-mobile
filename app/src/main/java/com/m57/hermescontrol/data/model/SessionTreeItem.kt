package com.m57.hermescontrol.data.model

data class SessionTreeItem(
    val session: SessionInfo,
    val depth: Int,
    val branchStem: String?,
    // True when this session was forked/branched from another (has a parent in
    // the loaded set). Drives the fork icon in SessionCard.
    val isFork: Boolean = false,
    // How many levels deep this fork sits (1 = direct child of a root, 2 = fork
    // of a fork, ...). Shown as the number next to the fork icon so a nested
    // chain reads 🍴1 → 🍴2 → 🍴3 and a branch-off pops out visually. 0 for
    // non-forked (root) sessions.
    val forkDepth: Int = 0,
    val displayTitle: String,
)

/**
 * Flatten a session list into a display-order tree.
 *
 * Branching is expressed by `parent_session_id`. A session is a "fork" when its
 * parent is present in the set; `forkDepth` is its nesting depth (1 = child of a
 * root). The caller (SessionCard) renders a fork icon + the depth number so
 * multiply-nested forks stay readable without deep indentation.
 */
fun flattenSessionTree(sessions: List<SessionInfo>): List<SessionTreeItem> {
    val ids = sessions.mapTo(mutableSetOf()) { it.id }
    val children = sessions.groupBy { it.parent_session_id?.takeIf(ids::contains) }
    val result = mutableListOf<SessionTreeItem>()
    val visited = mutableSetOf<String>()

    fun append(
        session: SessionInfo,
        depth: Int,
        siblingIndex: Int,
        siblingCount: Int,
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
        val branchStem = if (depth == 0) null else (if (siblingIndex == siblingCount - 1) "└─" else "├─")
        result +=
            SessionTreeItem(
                session = session,
                depth = depth,
                branchStem = branchStem,
                isFork = isBranch,
                forkDepth = if (isBranch) depth else 0,
                displayTitle = title,
            )
        val descendants = children[session.id].orEmpty()
        descendants.forEachIndexed { index, child ->
            append(
                session = child,
                depth = depth + 1,
                siblingIndex = index,
                siblingCount = descendants.size,
            )
        }
    }

    children[null].orEmpty().forEachIndexed { index, root ->
        append(root, depth = 0, siblingIndex = index, siblingCount = children[null].orEmpty().size)
    }
    sessions.filterNot { it.id in visited }.forEachIndexed { index, orphan ->
        append(orphan, depth = 0, siblingIndex = index, siblingCount = 1)
    }
    return result
}
