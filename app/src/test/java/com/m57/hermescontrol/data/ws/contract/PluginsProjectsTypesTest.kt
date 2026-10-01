package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsProjectsTypesTest {
    private fun encode(params: PluginsManageParams) =
        OkHttpProvider.json.encodeToJsonElement(PluginsManageParams.serializer(), params)

    @Test
    fun listEncodesOnlyAction() {
        assertEquals(buildJsonObject { put("action", "list") }, encode(PluginsManageParams(action = "list")))
    }

    @Test
    fun removeEncodesActionAndName() {
        assertEquals(
            buildJsonObject {
                put("action", "remove")
                put("name", "demo")
            },
            encode(PluginsManageParams(action = "remove", name = "demo")),
        )
    }

    @Test
    fun settingsEncodesKeyValuesAndExplicitProfile() {
        val values = buildJsonObject { put("port", JsonPrimitive(8080)) }
        assertEquals(
            buildJsonObject {
                put("action", "settings")
                put("profile", "staging")
                put("key", "camofox")
                put("values", values)
            },
            encode(PluginsManageParams(action = "settings", key = "camofox", values = values, profile = "staging")),
        )
    }

    @Test
    fun projectsListEncodesAsEmptyObject() {
        assertEquals(
            buildJsonObject { },
            OkHttpProvider.json.encodeToJsonElement(ProjectsListParams.serializer(), ProjectsListParams),
        )
    }

    @Test
    fun descriptorsAreRegisteredWithContractNames() {
        assertTrue(RpcMethods.PLUGINS_MANAGE in RpcMethods.all)
        assertTrue(RpcMethods.PROJECTS_LIST in RpcMethods.all)
        assertEquals("plugins.manage", RpcMethods.PLUGINS_MANAGE.name)
        assertEquals("projects.list", RpcMethods.PROJECTS_LIST.name)
    }
}
