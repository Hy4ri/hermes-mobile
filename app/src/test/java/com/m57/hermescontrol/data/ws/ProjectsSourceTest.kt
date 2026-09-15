package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsSourceTest {
    @Test
    fun `decodes the untyped rpc map`() =
        runTest {
            var calledMethod: String? = null
            val source =
                HermesProjectsSource(isConnected = { true }) { method, _ ->
                    calledMethod = method
                    mapOf(
                        "projects" to
                            listOf(
                                mapOf(
                                    "id" to "p_1",
                                    "name" to "App",
                                    "color" to "hsl(1 2% 3%)",
                                    "archived" to false,
                                    "folders" to listOf(mapOf("path" to "/srv/app", "is_primary" to true)),
                                ),
                            ),
                        "active_id" to "p_1",
                    )
                }

            val projects = source.fetchProjects()

            assertEquals(WsMethods.PROJECTS_LIST, calledMethod)
            assertEquals(listOf("App"), projects?.map { it.name })
            assertEquals(
                "/srv/app",
                projects
                    ?.single()
                    ?.folders
                    ?.single()
                    ?.path,
            )
        }

    @Test
    fun `skips the rpc while the socket is not connected`() =
        runTest {
            var calls = 0
            val source =
                HermesProjectsSource(
                    rpcRequest = { _, _ ->
                        calls++
                        mapOf("projects" to emptyList<Any>())
                    },
                    isConnected = { false },
                )

            assertNull(source.fetchProjects())
            assertEquals("a disconnected fetch must not queue an RPC", 0, calls)
        }

    @Test
    fun `rpc failure or unexpected payload yields null`() =
        runTest {
            assertNull(
                HermesProjectsSource(isConnected = { true }) { _, _ -> error("method not found") }.fetchProjects(),
            )
            assertNull(HermesProjectsSource(isConnected = { true }) { _, _ -> "nope" }.fetchProjects())
            assertNull(HermesProjectsSource(isConnected = { true }) { _, _ -> null }.fetchProjects())
        }

    @Test
    fun `cancellation propagates`() =
        runTest {
            val thrown =
                runCatching {
                    HermesProjectsSource(
                        isConnected = { true },
                    ) { _, _ -> throw CancellationException("gone") }.fetchProjects()
                }.exceptionOrNull()
            assertTrue(thrown is CancellationException)
        }
}
