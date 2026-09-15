package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-shape tests for the session workspace fields and the `projects.list` RPC result. */
class ProjectModelsTest {
    private val json = OkHttpProvider.json

    @Test
    fun `session list row decodes its workspace`() {
        val session =
            json.decodeFromString<SessionInfo>(
                """{"id":"s1","cwd":"/srv/app/src","git_repo_root":"/srv/app","git_branch":"main"}""",
            )

        assertEquals("/srv/app/src", session.cwd)
        assertEquals("/srv/app", session.git_repo_root)
    }

    @Test
    fun `session list row without a workspace decodes nulls`() {
        val session = json.decodeFromString<SessionInfo>("""{"id":"s1"}""")

        assertNull(session.cwd)
        assertNull(session.git_repo_root)
    }

    @Test
    fun `projects list payload decodes folders, color and archived`() {
        val response =
            json.decodeFromString<ProjectsListResponse>(
                """
                {"projects":[
                  {"id":"p_1","slug":"app","name":"App","description":null,"icon":"rocket",
                   "color":"hsl(120 68% 58%)","board_slug":null,"primary_path":"/srv/app",
                   "archived":false,"created_at":1787590315,
                   "folders":[{"path":"/srv/app","label":null,"is_primary":true,"added_at":1787590315}]},
                  {"id":"p_2","slug":"old","name":"Old","archived":1,"folders":[]}
                ],"active_id":"p_1"}
                """.trimIndent(),
            )

        val app = response.projects[0]
        assertEquals("App", app.name)
        assertEquals("hsl(120 68% 58%)", app.color)
        assertEquals("/srv/app", app.primaryPath)
        assertEquals(listOf("/srv/app"), app.folders.map { it.path })
        assertTrue(app.folders[0].isPrimary == true)
        assertFalse(app.isArchived)
        assertTrue(response.projects[1].isArchived)
        assertEquals("p_1", response.activeId)
    }

    @Test
    fun `project with only an id and name decodes defaults`() {
        val project = json.decodeFromString<ProjectInfo>("""{"id":"p_3","name":"Bare"}""")

        assertNull(project.color)
        assertTrue(project.folders.isEmpty())
        assertFalse(project.isArchived)
    }
}
