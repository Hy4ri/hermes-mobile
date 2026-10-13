package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.WsProfileParams
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandsTypesTest {
    private val json = OkHttpProvider.json

    @Test
    fun commandsCatalogEncodesAsEmptyObject() {
        assertEquals(
            buildJsonObject { },
            json.encodeToJsonElement(CommandsCatalogParams.serializer(), CommandsCatalogParams()),
        )
    }

    @Test
    fun commandsCatalogNullOmittedAndExplicitValuesFaithfullyEncoded() {
        // null / default omits fields
        assertEquals(
            buildJsonObject { },
            json.encodeToJsonElement(
                CommandsCatalogParams.serializer(),
                CommandsCatalogParams(sessionId = null, profile = null),
            ),
        )

        // explicit session_id encoded, null profile omitted
        assertEquals(
            buildJsonObject {
                put("session_id", "s-123")
            },
            json.encodeToJsonElement(
                CommandsCatalogParams.serializer(),
                CommandsCatalogParams(sessionId = "s-123"),
            ),
        )

        // explicit profile encoded, null session_id omitted
        assertEquals(
            buildJsonObject {
                put("profile", "work")
            },
            json.encodeToJsonElement(
                CommandsCatalogParams.serializer(),
                CommandsCatalogParams(profile = "work"),
            ),
        )

        // both explicit session_id and profile faithfully encoded
        assertEquals(
            buildJsonObject {
                put("session_id", "s-123")
                put("profile", "work")
            },
            json.encodeToJsonElement(
                CommandsCatalogParams.serializer(),
                CommandsCatalogParams(sessionId = "s-123", profile = "work"),
            ),
        )

        // explicit empty strings are preserved faithfully
        assertEquals(
            buildJsonObject {
                put("session_id", "")
                put("profile", "")
            },
            json.encodeToJsonElement(
                CommandsCatalogParams.serializer(),
                CommandsCatalogParams(sessionId = "", profile = ""),
            ),
        )
    }

    @Test
    fun commandDispatchKeepsEmptyArgAndOmitsNullOnes() {
        assertEquals(
            buildJsonObject {
                put("name", "help")
                put("arg", "")
                put("session_id", "s1")
            },
            json.encodeToJsonElement(
                CommandDispatchParams.serializer(),
                CommandDispatchParams(name = "help", arg = "", sessionId = "s1"),
            ),
        )
        assertEquals(
            buildJsonObject { put("name", "help") },
            json.encodeToJsonElement(CommandDispatchParams.serializer(), CommandDispatchParams(name = "help")),
        )
    }

    @Test
    fun slashExecEncodesSessionAndCommand() {
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("command", "/status")
            },
            json.encodeToJsonElement(
                SlashExecParams.serializer(),
                SlashExecParams(sessionId = "s1", command = "/status"),
            ),
        )
    }

    @Test
    fun fileAttachSendsDataUrlAndNameWithoutPath() {
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("data_url", "data:text/plain;base64,QQ==")
                put("name", "a.txt")
            },
            json.encodeToJsonElement(
                FileAttachParams.serializer(),
                FileAttachParams(sessionId = "s1", dataUrl = "data:text/plain;base64,QQ==", name = "a.txt"),
            ),
        )
    }

    @Test
    fun imageAttachBytesEncodesContentBase64FilenameAndExt() {
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("content_base64", "data:image/png;base64,QQ==")
                put("filename", "p.png")
                put("ext", "png")
            },
            json.encodeToJsonElement(
                ImageAttachBytesParams.serializer(),
                ImageAttachBytesParams(
                    sessionId = "s1",
                    contentBase64 = "data:image/png;base64,QQ==",
                    filename = "p.png",
                    ext = "png",
                ),
            ),
        )
    }

    @Test
    fun commandsCatalogExplicitProfileWinsOverDecorator() {
        // decorate() reads AuthManager.activeProfileId (Hermes profile), not the connection profile.
        AuthManager.setActiveProfileId("active-profile")
        try {
            // 1. Without explicit profile, WsProfileParams.decorate injects the active profile
            val emptyMapParams = emptyMap<String, Any>()
            val decorated = WsProfileParams.decorate(WsMethods.COMMANDS_CATALOG, emptyMapParams)
            assertEquals("active-profile", decorated["profile"])

            // 2. With explicit profile in map, explicit captured profile wins
            val explicitProfileParams = mapOf("profile" to "explicit-custom", "session_id" to "s1")
            val preserved = WsProfileParams.decorate(WsMethods.COMMANDS_CATALOG, explicitProfileParams)
            assertEquals("explicit-custom", preserved["profile"])
            assertEquals("s1", preserved["session_id"])

            // 3. With explicit empty string profile, explicit wins over decorator
            val emptyStringProfileParams = mapOf("profile" to "")
            val preservedEmpty = WsProfileParams.decorate(WsMethods.COMMANDS_CATALOG, emptyStringProfileParams)
            assertEquals("", preservedEmpty["profile"])
        } finally {
            AuthManager.setActiveProfileId(null)
        }
    }

    @Test
    fun descriptorsAreRegisteredWithContractNames() {
        listOf(
            RpcMethods.COMMANDS_CATALOG to "commands.catalog",
            RpcMethods.COMMAND_DISPATCH to "command.dispatch",
            RpcMethods.SLASH_EXEC to "slash.exec",
            RpcMethods.FILE_ATTACH to "file.attach",
            RpcMethods.IMAGE_ATTACH_BYTES to "image.attach_bytes",
        ).forEach { (method, name) ->
            assertTrue("$name not in RpcMethods.all", method in RpcMethods.all)
            assertEquals(name, method.name)
        }
    }
}
