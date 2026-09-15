package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable

/** Result of the `projects.list` JSON-RPC: the profile's named workspaces. */
@Serializable
data class ProjectsListResponse(
    val projects: List<ProjectInfo> = emptyList(),
    val activeId: String? = null,
)

/**
 * A user-created project from the backend's per-profile `projects.db`. A
 * session belongs to the project whose folder contains its cwd or repo root.
 */
@Serializable
data class ProjectInfo(
    val id: String,
    val name: String = "",
    val slug: String? = null,
    // CSS color string chosen in the desktop app, e.g. "hsl(210 68% 58%)".
    val color: String? = null,
    val icon: String? = null,
    val primaryPath: String? = null,
    // Older backends store SQLite 0/1 here.
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val archived: Boolean? = null,
    val folders: List<ProjectFolder> = emptyList(),
) {
    val isArchived: Boolean get() = archived == true
}

@Serializable
data class ProjectFolder(
    val path: String,
    val label: String? = null,
    @Serializable(with = LenientNullableBooleanSerializer::class)
    val isPrimary: Boolean? = null,
)
