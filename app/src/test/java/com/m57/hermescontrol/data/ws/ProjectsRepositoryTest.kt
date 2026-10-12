package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.contract.ProjectsArchiveParams
import com.m57.hermescontrol.data.ws.contract.ProjectsCreateParams
import com.m57.hermescontrol.data.ws.contract.ProjectsUpdateParams
import com.m57.hermescontrol.data.ws.contract.RpcMethod
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcCaller
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsRepositoryTest {
    private class RecordingCaller(
        private val reply: (String) -> JsonElement = { JsonObject(emptyMap()) },
    ) : TypedRpcCaller {
        val calls = mutableListOf<Pair<String, JsonElement>>()

        override suspend fun <P, R> call(
            method: RpcMethod<P, R>,
            params: P,
        ): R {
            calls += method.name to OkHttpProvider.json.encodeToJsonElement(method.params, params)
            @Suppress("UNCHECKED_CAST")
            return reply(method.name) as R
        }
    }

    @Test
    fun createSendsNameFolderAndColorOnly() =
        runTest {
            val caller = RecordingCaller()
            ProjectsRepository(caller).create("App", "/code/app", "hsl(0 68% 58%)")
            val (method, params) = caller.calls.single()
            assertEquals("projects.create", method)
            assertEquals(
                OkHttpProvider.json.encodeToJsonElement(
                    ProjectsCreateParams.serializer(),
                    ProjectsCreateParams(name = "App", folders = listOf("/code/app"), color = "hsl(0 68% 58%)"),
                ),
                params,
            )
        }

    @Test
    fun updateClearsColorWithEmptyString() =
        runTest {
            val caller = RecordingCaller()
            ProjectsRepository(caller).update("p_1", "App", "", null)
            assertEquals(
                OkHttpProvider.json.encodeToJsonElement(
                    ProjectsUpdateParams.serializer(),
                    ProjectsUpdateParams(id = "p_1", name = "App", description = "", color = ""),
                ),
                caller.calls.single().second,
            )
        }

    @Test
    fun restoreSendsRestoreTrueAndArchiveOmitsIt() =
        runTest {
            val caller = RecordingCaller()
            val repo = ProjectsRepository(caller)
            repo.setArchived("p_1", archived = true)
            repo.setArchived("p_1", archived = false)
            val enc = { p: ProjectsArchiveParams ->
                OkHttpProvider.json.encodeToJsonElement(ProjectsArchiveParams.serializer(), p)
            }
            assertEquals(enc(ProjectsArchiveParams(id = "p_1")), caller.calls[0].second)
            assertEquals(enc(ProjectsArchiveParams(id = "p_1", restore = true)), caller.calls[1].second)
        }

    @Test
    fun gatewayErrorBecomesFailedWithItsMessage() =
        runTest {
            val caller =
                object : TypedRpcCaller {
                    override suspend fun <P, R> call(
                        method: RpcMethod<P, R>,
                        params: P,
                    ): R = throw HermesWsClient.HermesRpcException("folder already belongs to project 'app'", 5063)
                }
            val result = ProjectsRepository(caller).create("Dup", "/code/app", null)
            assertEquals(ProjectsResult.Failed("folder already belongs to project 'app'"), result)
        }

    @Test
    fun listDecodesArchivedAndFolders() =
        runTest {
            val payload =
                OkHttpProvider.json.parseToJsonElement(
                    """{"projects":[{"id":"p_1","name":"App","archived":true,
                    "folders":[{"path":"/code/app","is_primary":true}]}],"active_id":null}""",
                )
            val result = ProjectsRepository(RecordingCaller { payload }).list()
            val project = (result as ProjectsResult.Ok).value.single()
            assertTrue(project.isArchived)
            assertEquals("/code/app", project.folders.single().path)
        }

    @Test
    fun discoveredReposFallBackToEmptyOnError() =
        runTest {
            val caller =
                object : TypedRpcCaller {
                    override suspend fun <P, R> call(
                        method: RpcMethod<P, R>,
                        params: P,
                    ): R = throw HermesWsClient.HermesRpcException("method not found", -32601)
                }
            assertTrue(ProjectsRepository(caller).discoveredRepos().isEmpty())
        }

    @Test
    fun mutationDescriptorsAreRegistered() {
        val names = RpcMethods.all.map { it.name }.toSet()
        listOf(
            "projects.create",
            "projects.update",
            "projects.delete",
            "projects.archive",
            "projects.add_folder",
            "projects.remove_folder",
            "projects.set_primary",
            "projects.discover_repos",
        ).forEach { assertTrue("$it missing", it in names) }
    }
}
