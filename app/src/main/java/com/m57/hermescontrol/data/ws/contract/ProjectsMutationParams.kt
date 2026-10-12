package com.m57.hermescontrol.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Params for the gateway's `projects.*` mutations (tui_gateway/methods_projects.py). Unset
// optionals are omitted; [profile] is an explicit override only, WsProfileParams injects it.

/** `projects.create`: [folders] become the project's folders, the first one primary. */
@Serializable
data class ProjectsCreateParams(
    @SerialName("name") val name: String,
    @SerialName("profile") val profile: String? = null,
    @SerialName("folders") val folders: List<String>? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("color") val color: String? = null,
)

/** `projects.update`: null leaves a field untouched; `""` clears `color`. */
@Serializable
data class ProjectsUpdateParams(
    @SerialName("id") val id: String,
    @SerialName("profile") val profile: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("color") val color: String? = null,
)

/** `projects.delete`: hard-deletes the project and its folder links (sessions are kept). */
@Serializable
data class ProjectIdParams(
    @SerialName("id") val id: String,
    @SerialName("profile") val profile: String? = null,
)

/** `projects.archive`: archives, or restores when [restore] is true. */
@Serializable
data class ProjectsArchiveParams(
    @SerialName("id") val id: String,
    @SerialName("profile") val profile: String? = null,
    @SerialName("restore") val restore: Boolean? = null,
)

/** `projects.add_folder`. */
@Serializable
data class ProjectsAddFolderParams(
    @SerialName("id") val id: String,
    @SerialName("path") val path: String,
    @SerialName("profile") val profile: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean? = null,
)

/** `projects.remove_folder` / `projects.set_primary`. */
@Serializable
data class ProjectFolderParams(
    @SerialName("id") val id: String,
    @SerialName("path") val path: String,
    @SerialName("profile") val profile: String? = null,
)

/** `projects.discover_repos`: git roots the gateway already knows from sessions (no scan). */
@Serializable
data class ProjectsDiscoverReposParams(
    @SerialName("profile") val profile: String? = null,
)
