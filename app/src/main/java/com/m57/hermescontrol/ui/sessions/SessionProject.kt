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
    val cwd = session.cwd?.trim().orEmpty()
    val recordedRoot = session.git_repo_root?.trim().orEmpty()
    val repoRoot = recordedRoot.ifEmpty { cwd }
    if (cwd.isEmpty() && repoRoot.isEmpty()) return null

    val owner =
        projects
            .filterNot { it.isArchived }
            .flatMap { project -> project.folders.map { project to it.path } }
            .filter { (_, folder) -> isPathUnder(folder, cwd) || isPathUnder(folder, repoRoot) }
            .maxByOrNull { (_, folder) -> pathSegments(folder).size }

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

/** Path segments, ignoring mixed separators, repeated separators and trailing slashes. */
private fun pathSegments(path: String): List<String> = path.trim().split('/', '\\').filter(String::isNotEmpty)

private val WindowsDrive = Regex("^[A-Za-z]:[/\\\\]")

/** Drive-letter (`C:\…`), UNC (`\\srv`, `//srv`) or backslash-rooted paths, as the backend classifies them. */
private fun isWindowsPath(path: String): Boolean {
    val trimmed = path.trim()
    return WindowsDrive.containsMatchIn(trimmed) || trimmed.startsWith("\\") || trimmed.startsWith("//")
}

/**
 * Segments for identity comparison. Windows paths fold case so `C:\Work` and `c:/work` are one
 * folder; POSIX stays case-sensitive. Labels keep the recorded spelling.
 */
private fun comparisonSegments(path: String): List<String> {
    val segments = pathSegments(path)
    return if (isWindowsPath(path)) segments.map { it.lowercase() } else segments
}

private fun isPathUnder(
    folder: String,
    target: String,
): Boolean {
    val folderSegments = comparisonSegments(folder)
    val targetSegments = comparisonSegments(target)
    if (folderSegments.isEmpty() || folderSegments.size > targetSegments.size) return false
    return folderSegments.indices.all { folderSegments[it] == targetSegments[it] }
}

/** Last segment in its recorded spelling; the filesystem root stays as written ("/"). */
private fun pathLeaf(path: String): String = pathSegments(path).lastOrNull() ?: path.trim()
