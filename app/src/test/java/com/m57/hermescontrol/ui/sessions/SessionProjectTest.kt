package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.ProjectFolder
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionProjectTest {
    private fun session(
        cwd: String? = null,
        repoRoot: String? = null,
    ) = SessionInfo(id = "s", cwd = cwd, git_repo_root = repoRoot)

    private fun project(
        name: String,
        vararg folders: String,
        color: String? = null,
        archived: Boolean = false,
    ) = ProjectInfo(
        id = "p_$name",
        name = name,
        color = color,
        archived = archived,
        folders = folders.map { ProjectFolder(path = it) },
    )

    @Test
    fun `explicit project owns a cwd under its folder`() {
        val projects = listOf(project("App", "/srv/app", color = "hsl(1 2% 3%)"))

        assertEquals(
            SessionProject(label = "App", color = "hsl(1 2% 3%)"),
            resolveSessionProject(session(cwd = "/srv/app/src"), projects),
        )
    }

    @Test
    fun `explicit project matches on the repo root when the cwd sits outside`() {
        val projects = listOf(project("App", "/srv/app"))

        assertEquals(
            "App",
            resolveSessionProject(session(cwd = "/tmp/worktree", repoRoot = "/srv/app"), projects)?.label,
        )
    }

    @Test
    fun `deepest folder wins for nested projects`() {
        val projects = listOf(project("Mono", "/srv/mono"), project("Web", "/srv/mono/web"))

        assertEquals("Web", resolveSessionProject(session(cwd = "/srv/mono/web/src"), projects)?.label)
    }

    @Test
    fun `archived projects are skipped`() {
        val projects = listOf(project("Root", "/root", archived = true))

        assertEquals("notes", resolveSessionProject(session(cwd = "/root/notes"), projects)?.label)
    }

    @Test
    fun `sibling directory with a shared prefix is not under the folder`() {
        val projects = listOf(project("Foo", "/root/foo"))

        assertEquals("foobar", resolveSessionProject(session(cwd = "/root/foobar"), projects)?.label)
    }

    @Test
    fun `trailing slashes do not break folder matching`() {
        val projects = listOf(project("App", "/srv/app/"))

        assertEquals("App", resolveSessionProject(session(cwd = "/srv/app/"), projects)?.label)
    }

    @Test
    fun `unowned git session names its repo root`() {
        assertEquals(
            SessionProject(label = "hermes-agent", color = null),
            resolveSessionProject(
                session(cwd = "/code/hermes-agent/tools", repoRoot = "/code/hermes-agent"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `worktree outside its repo still names the repo`() {
        assertEquals(
            "hermes-agent",
            resolveSessionProject(session(cwd = "/code/wt-123", repoRoot = "/code/hermes-agent"), emptyList())?.label,
        )
    }

    @Test
    fun `repo root only session names the root`() {
        assertEquals("app", resolveSessionProject(session(repoRoot = "/srv/app"), emptyList())?.label)
    }

    @Test
    fun `non git session names its cwd leaf`() {
        assertEquals("scratch", resolveSessionProject(session(cwd = "/home/me/scratch"), emptyList())?.label)
    }

    @Test
    fun `windows paths resolve`() {
        val projects = listOf(project("Win", "C:\\code\\app"))

        assertEquals("Win", resolveSessionProject(session(cwd = "C:\\code\\app\\src"), projects)?.label)
    }

    @Test
    fun `session without a workspace has no project`() {
        assertNull(resolveSessionProject(session(), listOf(project("App", "/srv/app"))))
        assertNull(resolveSessionProject(session(cwd = "  ", repoRoot = ""), emptyList()))
    }

    @Test
    fun `blank explicit name falls back to the folder leaf`() {
        val projects = listOf(project(" ", "/srv/app"))

        assertEquals("app", resolveSessionProject(session(cwd = "/srv/app"), projects)?.label)
    }
}
