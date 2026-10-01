package com.m57.hermescontrol.data.ws

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsSourceTest {
    private fun projectsSource(
        connected: Boolean = true,
        handler: suspend (String, Map<String, Any>) -> Any?,
    ) = HermesProjectsSource(isConnected = { connected }, caller = fakeCaller(handler))

    @Test
    fun `decodes the untyped rpc map`() =
        runTest {
            var calledMethod: String? = null
            val source =
                projectsSource { method, _ ->
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
                projectsSource(connected = false) { _, _ ->
                    calls++
                    mapOf("projects" to emptyList<Any>())
                }

            assertNull(source.fetchProjects())
            assertEquals("a disconnected fetch must not queue an RPC", 0, calls)
        }

    @Test
    fun `rpc failure or unexpected payload yields null`() =
        runTest {
            assertNull(
                projectsSource { _, _ -> error("method not found") }.fetchProjects(),
            )
            assertNull(projectsSource { _, _ -> "nope" }.fetchProjects())
            assertNull(projectsSource { _, _ -> null }.fetchProjects())
        }

    @Test
    fun `cancellation propagates`() =
        runTest {
            val thrown =
                runCatching {
                    projectsSource { _, _ -> throw CancellationException("gone") }.fetchProjects()
                }.exceptionOrNull()
            assertTrue(thrown is CancellationException)
        }
}
