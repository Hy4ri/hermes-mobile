package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.ProjectsListResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Supplies the profile's named projects so history rows can show which one a chat belongs to. */
interface ProjectsSource {
    /** The project list, or null when the backend can't answer (offline, older server). */
    suspend fun fetchProjects(): List<ProjectInfo>?
}

/** [ProjectsSource] backed by the gateway's `projects.list` JSON-RPC. */
class HermesProjectsSource(
    // Only ask a live socket: an offline request would be queued and force a reconnect on
    // every refresh. The view model refetches when the connection comes back.
    private val isConnected: () -> Boolean = { HermesWsClient.connectionStatus.value == ConnectionStatus.CONNECTED },
    private val rpcRequest: suspend (method: String, params: Map<String, Any>) -> Any? = { method, params ->
        HermesWsClient.request(method, params).await()
    },
) : ProjectsSource {
    override suspend fun fetchProjects(): List<ProjectInfo>? {
        if (!isConnected()) return null
        return try {
            val element = SessionLiveStatusDecoder.anyToJsonElement(rpcRequest(WsMethods.PROJECTS_LIST, emptyMap()))
            if (element is JsonObject && element.containsKey("projects")) {
                OkHttpProvider.json.decodeFromJsonElement(ProjectsListResponse.serializer(), element).projects
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
