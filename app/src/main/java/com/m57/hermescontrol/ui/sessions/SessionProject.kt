package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo

/** The workspace a history row belongs to: a display label plus the project's own color, if any. */
data class SessionProject(
    val label: String,
    val color: String? = null,
)

/**
 * Names the project a session belongs to, mirroring the desktop sidebar card
 * (`liveSessionProjectId` + `sessionProjectLabel`):
 *
 * 1. the non-archived project whose folder contains the cwd or the repo root
 *    (deepest folder wins, so nested projects resolve to the innermost one);
 * 2. the repo root itself when the cwd sits under it (an auto project);
 * 3. the recorded repo root's leaf (a worktree outside its repo still names it);
 * 4. the cwd's leaf.
 *
 * Returns null when the session recorded no workspace; the card shows "Home".
 */
fun resolveSessionProject(
    session: SessionInfo,
    projects: List<ProjectInfo>,
): SessionProject? {
    val cwd = normalizePath(session.cwd)
    val recordedRoot = normalizePath(session.git_repo_root)
    val repoRoot = recordedRoot.ifEmpty { cwd }
    if (cwd.isEmpty() && repoRoot.isEmpty()) return null

    val owner =
        projects
            .filterNot { it.isArchived }
            .flatMap { project -> project.folders.map { project to normalizePath(it.path) } }
            .filter { (_, folder) ->
                folder.isNotEmpty() && (isPathUnder(folder, cwd) || isPathUnder(folder, repoRoot))
            }.maxByOrNull { (_, folder) -> pathSegments(folder) }

    if (owner != null) {
        val (project, folder) = owner
        val label = project.name.trim().ifEmpty { pathLeaf(folder) }
        return SessionProject(label = label, color = project.color)
    }

    val autoRoot = repoRoot.takeIf { cwd.isEmpty() || isPathUnder(it, cwd) }
    val label =
        listOf(autoRoot, recordedRoot, cwd)
            .firstNotNullOfOrNull { path -> path?.let(::pathLeaf)?.takeIf(String::isNotEmpty) }
            ?: return null
    return SessionProject(label = label)
}

/** Forward slashes, trimmed, no trailing separator (the filesystem root stays "/"). */
private fun normalizePath(raw: String?): String {
    val path = raw?.trim()?.replace('\\', '/').orEmpty()
    return path.trimEnd('/').ifEmpty { if (path.startsWith("/")) "/" else "" }
}

private fun isPathUnder(
    parent: String,
    child: String,
): Boolean {
    if (parent.isEmpty() || child.isEmpty()) return false
    if (child == parent) return true
    val prefix = if (parent.endsWith("/")) parent else "$parent/"
    return child.startsWith(prefix)
}

private fun pathSegments(path: String): Int = path.split('/').count { it.isNotEmpty() }

private fun pathLeaf(path: String): String = path.substringAfterLast('/').ifEmpty { path }
