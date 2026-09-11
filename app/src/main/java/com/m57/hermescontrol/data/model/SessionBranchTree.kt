package com.m57.hermescontrol.data.model

private fun recency(s: SessionInfo): Double = s.last_active ?: s.started_at ?: 0.0

/**
 * Port of desktop session-branch-tree.ts#flattenSessionsWithBranches.
 *
 * - Aliases _lineage_root_id so forks find compression-projected parents.
 * - Sorts sibling branches by own recency; sorts roots by groupRecency
 *   (max over subtree) so activity on any branch lifts the whole family.
 * - preserveOrder keeps caller-chosen root order (pinned section).
 * - Cycle-guarded; trailing sweep never drops an input row.
 */
fun flattenSessionsWithBranches(
    sessions: List<SessionInfo>,
    preserveOrder: Boolean = false,
): List<SessionTreeItem> {
    if (sessions.size < 2) {
        return sessions.map { it.toTreeItem(depth = 0, siblingIndex = 0, siblingCount = 1) }
    }

    val byVisibleId = HashMap<String, SessionInfo>(sessions.size * 2)
    for (s in sessions) {
        byVisibleId[s.id] = s
        s.lineageRootId
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { byVisibleId[it] = s }
    }

    val childrenByParent = HashMap<String, MutableList<SessionInfo>>()
    val nestedIds = HashSet<String>()
    for (s in sessions) {
        val parentId = s.parent_session_id?.trim()?.takeIf(String::isNotEmpty) ?: continue
        val parent = byVisibleId[parentId] ?: continue
        if (parent.id == s.id) continue
        nestedIds += s.id
        childrenByParent.getOrPut(parent.id) { mutableListOf() } += s
    }
    childrenByParent.values.forEach { it.sortByDescending(::recency) }

    // Group sorts by its freshest member; memo pre-seed doubles as cycle guard.
    val groupRecencyMemo = HashMap<String, Double>()

    fun groupRecency(s: SessionInfo): Double {
        groupRecencyMemo[s.id]?.let { return it }
        groupRecencyMemo[s.id] = recency(s)
        val max =
            childrenByParent[s.id]
                .orEmpty()
                .fold(recency(s)) { acc, child -> maxOf(acc, groupRecency(child)) }
        groupRecencyMemo[s.id] = max
        return max
    }

    val out = mutableListOf<SessionTreeItem>()
    val seen = HashSet<String>()

    fun emit(
        s: SessionInfo,
        depth: Int,
        siblingIndex: Int,
        siblingCount: Int,
    ) {
        if (!seen.add(s.id)) return
        out += s.toTreeItem(depth, siblingIndex, siblingCount)
        val kids = childrenByParent[s.id].orEmpty()
        kids.forEachIndexed { i, child -> emit(child, depth + 1, i, kids.size) }
    }

    val roots = sessions.withIndex().filter { it.value.id !in nestedIds }
    val ordered =
        if (preserveOrder) {
            roots
        } else {
            roots.sortedWith(
                compareByDescending<IndexedValue<SessionInfo>> { groupRecency(it.value) }
                    .thenBy { it.index },
            )
        }
    ordered.forEachIndexed { i, r -> emit(r.value, 0, i, ordered.size) }
    sessions.filter { it.id !in seen }.forEach { emit(it, 0, 0, 1) } // trailing sweep
    return out
}

private fun SessionInfo.toTreeItem(
    depth: Int,
    siblingIndex: Int,
    siblingCount: Int,
): SessionTreeItem {
    // G4 fix: fork identity from the field itself, never from load-set membership.
    val isFork = !parent_session_id.isNullOrBlank()
    val title =
        title?.takeIf(String::isNotBlank)
            ?: display_name?.takeIf(String::isNotBlank)
            ?: if (isFork && depth > 0) {
                "branch ${siblingIndex + 1}"
            } else {
                preview?.takeIf(String::isNotBlank)?.take(80) ?: "Untitled"
            }
    return SessionTreeItem(
        session = this,
        depth = depth,
        branchStem =
            if (depth == 0) {
                null
            } else if (siblingIndex == siblingCount - 1) {
                "└─"
            } else {
                "├─"
            },
        isFork = isFork,
        forkDepth = if (depth > 0) depth else 0,
        displayTitle = title,
    )
}
