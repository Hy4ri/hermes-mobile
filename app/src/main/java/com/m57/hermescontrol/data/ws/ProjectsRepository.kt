package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.DiscoveredRepo
import com.m57.hermescontrol.data.model.DiscoveredReposResponse
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.ProjectsListResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.contract.HermesRpcCaller
import com.m57.hermescontrol.data.ws.contract.ProjectFolderParams
import com.m57.hermescontrol.data.ws.contract.ProjectIdParams
import com.m57.hermescontrol.data.ws.contract.ProjectsAddFolderParams
import com.m57.hermescontrol.data.ws.contract.ProjectsArchiveParams
import com.m57.hermescontrol.data.ws.contract.ProjectsCreateParams
import com.m57.hermescontrol.data.ws.contract.ProjectsDiscoverReposParams
import com.m57.hermescontrol.data.ws.contract.ProjectsListParams
import com.m57.hermescontrol.data.ws.contract.ProjectsUpdateParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Outcome of a projects RPC: the value, or the gateway's message (e.g. "folder already belongs to …"). */
sealed interface ProjectsResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ProjectsResult<T>

    data class Failed(
        val message: String,
    ) : ProjectsResult<Nothing>
}

/** Create / edit / archive / delete projects and their folders over the gateway's `projects.*` RPCs. */
class ProjectsRepository(
    private val caller: TypedRpcCaller = HermesRpcCaller,
) {
    /** Every project, archived included (the gateway returns both). */
    suspend fun list(): ProjectsResult<List<ProjectInfo>> =
        run { decodeProjects(caller.call(RpcMethods.PROJECTS_LIST, ProjectsListParams)) }

    /** Git roots seen in past sessions that are not a project yet; empty on gateways without it. */
    suspend fun discoveredRepos(): List<DiscoveredRepo> =
        (
            run {
                val element = caller.call(RpcMethods.PROJECTS_DISCOVER_REPOS, ProjectsDiscoverReposParams())
                OkHttpProvider.json.decodeFromJsonElement(DiscoveredReposResponse.serializer(), element).repos
            } as? ProjectsResult.Ok
        )?.value.orEmpty()

    suspend fun create(
        name: String,
        folder: String?,
        color: String?,
    ): ProjectsResult<Unit> =
        run {
            caller.call(
                RpcMethods.PROJECTS_CREATE,
                ProjectsCreateParams(
                    name = name,
                    folders = folder?.let(::listOf),
                    color = color,
                ),
            )
        }.unit()

    /** Patches name / description / color. A null color clears it on the gateway (sent as ""). */
    suspend fun update(
        id: String,
        name: String,
        description: String,
        color: String?,
    ): ProjectsResult<Unit> =
        run {
            caller.call(
                RpcMethods.PROJECTS_UPDATE,
                ProjectsUpdateParams(id = id, name = name, description = description, color = color.orEmpty()),
            )
        }.unit()

    suspend fun setArchived(
        id: String,
        archived: Boolean,
    ): ProjectsResult<Unit> =
        run {
            caller.call(
                RpcMethods.PROJECTS_ARCHIVE,
                ProjectsArchiveParams(id = id, restore = if (archived) null else true),
            )
        }.unit()

    suspend fun delete(id: String): ProjectsResult<Unit> =
        run { caller.call(RpcMethods.PROJECTS_DELETE, ProjectIdParams(id = id)) }.unit()

    suspend fun addFolder(
        id: String,
        path: String,
    ): ProjectsResult<Unit> =
        run { caller.call(RpcMethods.PROJECTS_ADD_FOLDER, ProjectsAddFolderParams(id = id, path = path)) }.unit()

    suspend fun removeFolder(
        id: String,
        path: String,
    ): ProjectsResult<Unit> =
        run { caller.call(RpcMethods.PROJECTS_REMOVE_FOLDER, ProjectFolderParams(id = id, path = path)) }.unit()

    suspend fun setPrimaryFolder(
        id: String,
        path: String,
    ): ProjectsResult<Unit> =
        run { caller.call(RpcMethods.PROJECTS_SET_PRIMARY, ProjectFolderParams(id = id, path = path)) }.unit()

    private fun decodeProjects(element: JsonElement): List<ProjectInfo> {
        require(element is JsonObject && element.containsKey("projects")) { "Unexpected projects payload" }
        return OkHttpProvider.json.decodeFromJsonElement(ProjectsListResponse.serializer(), element).projects
    }

    private inline fun <T> run(block: () -> T): ProjectsResult<T> =
        try {
            ProjectsResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProjectsResult.Failed(e.message?.takeIf(String::isNotBlank) ?: "Request failed")
        }

    private fun ProjectsResult<*>.unit(): ProjectsResult<Unit> =
        when (this) {
            is ProjectsResult.Ok -> ProjectsResult.Ok(Unit)
            is ProjectsResult.Failed -> this
        }
}
