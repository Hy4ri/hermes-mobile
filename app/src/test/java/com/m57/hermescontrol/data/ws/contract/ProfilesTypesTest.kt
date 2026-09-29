package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTypesTest {
    private fun encode(params: ProfilesConfigureParams) =
        OkHttpProvider.json.encodeToJsonElement(ProfilesConfigureParams.serializer(), params)

    @Test
    fun configureEncodesNameAndUiMetaOnly() {
        val meta = buildJsonObject { put("hermes-bots-groups", buildJsonObject { put("version", 3) }) }
        assertEquals(
            buildJsonObject {
                put("name", "default")
                put("ui_meta", meta)
            },
            encode(ProfilesConfigureParams(name = "default", uiMeta = meta)),
        )
    }

    @Test
    fun configureIncludesSoulWhenPresent() {
        val meta = buildJsonObject { put("hermes-bots", buildJsonObject { put("title", "Scout") }) }
        assertEquals(
            buildJsonObject {
                put("name", "scout")
                put("ui_meta", meta)
                put("soul", "# Scout")
            },
            encode(ProfilesConfigureParams(name = "scout", uiMeta = meta, soul = "# Scout")),
        )
    }

    @Test
    fun listEncodesAsEmptyObject() {
        assertEquals(
            buildJsonObject { },
            OkHttpProvider.json.encodeToJsonElement(ProfilesListParams.serializer(), ProfilesListParams),
        )
    }

    @Test
    fun descriptorsAreRegisteredWithContractNames() {
        assertTrue(RpcMethods.PROFILES_LIST in RpcMethods.all)
        assertTrue(RpcMethods.PROFILES_CONFIGURE in RpcMethods.all)
        assertEquals("profiles.list", RpcMethods.PROFILES_LIST.name)
        assertEquals("profiles.configure", RpcMethods.PROFILES_CONFIGURE.name)
    }
}
