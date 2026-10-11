package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.ws.contract.SessionCreateParams
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectLaunchTest {
    private fun project(
        id: String,
        vararg paths: String,
        archived: Boolean? = null,
    ) = ProjectInfo(id = id, archived = archived, folders = paths.map { ProjectFolder(path = it) })

    @Test
    fun launchCwd_isFirstFolder_orNull() {
        assertEquals("/a", project("p", "/a", "/b").launchCwd)
        assertNull(project("p").launchCwd)
        assertNull(project("p", " ").launchCwd)
    }

    @Test
    fun startable_dropsArchivedAndFolderless() {
        val all = listOf(project("ok", "/a"), project("old", "/b", archived = true), project("empty"))
        assertEquals(listOf("ok"), all.startable().map { it.id })
    }

    @Test
    fun createParams_sendCwdOnlyWhenSet() {
        val json = Json { encodeDefaults = false }
        assertEquals(
            """{"source":"desktop","cwd":"/a","cwd_explicit":true}""",
            json.encodeToString(
                SessionCreateParams.serializer(),
                SessionCreateParams(source = "desktop", cwd = "/a", cwdExplicit = true),
            ),
        )
        assertEquals(
            """{"source":"desktop"}""",
            json.encodeToString(SessionCreateParams.serializer(), SessionCreateParams(source = "desktop")),
        )
    }
}
