package com.m57.hermescontrol.data.ws.contract

import com.m57.hermescontrol.data.remote.OkHttpProvider
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
            json.encodeToJsonElement(CommandsCatalogParams.serializer(), CommandsCatalogParams),
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
