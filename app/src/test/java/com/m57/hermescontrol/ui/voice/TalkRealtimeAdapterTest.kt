package com.m57.hermescontrol.ui.voice

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkRealtimeAdapterTest {
    @Test
    fun `ASR and server VAD response never stage or request a client response`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(event("input_audio_buffer.committed"))
            seam.adapter.handle(
                json(
                    """{"type":"conversation.item.input_audio_transcription.completed","item_id":"audio-1",
                    "transcript":"Use the global assistant"}""",
                ),
            )
            seam.adapter.handle(created())
            seam.adapter.handle(done())

            assertEquals(listOf(Triple("user", "Use the global assistant", true)), seam.transcripts)
            assertTrue(seam.posts.isEmpty())
            assertTrue(seam.sent.isEmpty())
        }

    @Test
    fun `duplicate tool events dispatch effect once and continue only after response done`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(created())
            seam.adapter.handle(tool())
            seam.adapter.handle(tool())
            assertEquals(1, seam.posts.size)
            assertTrue(seam.sent.isEmpty())

            seam.adapter.handle(done(call("call-1")))
            seam.adapter.handle(done(call("call-1")))
            seam.adapter.handle(tool())
            assertEquals(1, seam.posts.size)
            assertEquals(listOf("call-1"), seam.outputs().map { it.obj("item").text("call_id") })
            assertEquals(1, seam.creates().size)
            assertEquals(listOf("conversation.item.create", "response.create"), seam.sent.map { it.text("type") })
        }

    @Test
    fun `late arguments honor response done barrier and declaration order`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(created())
            seam.adapter.handle(done(call("first"), call("second")))
            seam.adapter.handle(tool("second"))
            assertTrue(seam.sent.isEmpty())
            seam.adapter.handle(tool("first"))

            assertEquals(listOf("first", "second"), seam.outputs().map { it.obj("item").text("call_id") })
            assertEquals(1, seam.creates().size)
            assertEquals("response.create", seam.sent.last().text("type"))
        }

    @Test
    fun `arguments before done still emit function outputs in declaration order`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(created())
            seam.adapter.handle(tool("second"))
            seam.adapter.handle(tool("first"))
            assertTrue(seam.sent.isEmpty())
            seam.adapter.handle(done(call("first"), call("second")))

            assertEquals(listOf("first", "second"), seam.outputs().map { it.obj("item").text("call_id") })
            assertEquals(1, seam.creates().size)
        }

    @Test
    fun `global tool POST has only name and arguments without binding identifiers`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(created())
            seam.adapter.handle(tool(arguments = """{"task":"explain this"}"""))
            seam.adapter.handle(done(call("call-1", arguments = """{"task":"explain this"}""")))

            val (path, body) = seam.posts.single()
            assertEquals("/tool", path)
            assertEquals(setOf("name", "arguments"), body.keys)
            assertEquals("delegate_task", body.text("name"))
            assertEquals(json("""{"task":"explain this"}"""), body.obj("arguments"))
            assertEquals(setOf("type"), seam.creates().single().keys)
        }

    @Test
    fun `uncertain tool transport produces truthful safe output and never retries`() =
        runTest {
            val seam = Seam(failTool = true)
            seam.adapter.handle(created())
            seam.adapter.handle(tool())
            seam.adapter.handle(tool())
            seam.adapter.handle(done(call("call-1")))

            assertEquals(1, seam.posts.size)
            val output =
                seam
                    .outputs()
                    .single()
                    .obj("item")
                    .text("output")
            assertTrue(output.contains("uncertain"))
            assertFalse(output.contains("secret-server-body"))
            assertFalse(seam.errors.joinToString().contains("secret-server-body"))
            assertEquals(1, seam.creates().size)
        }

    @Test
    fun `conflicting duplicate arguments never repeat an accepted request`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(created())
            seam.adapter.handle(tool())
            seam.adapter.handle(tool(arguments = """{"different":true}"""))
            seam.adapter.handle(done(call("call-1")))

            assertEquals(1, seam.posts.size)
            assertTrue(
                seam
                    .outputs()
                    .single()
                    .obj("item")
                    .text("output")
                    .contains("conflict", ignoreCase = true),
            )
        }

    @Test
    fun `malformed arguments are not executed as an empty request`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(created())
            seam.adapter.handle(tool(arguments = "[1]"))
            seam.adapter.handle(done(call("call-1", arguments = "[1]")))

            assertTrue(seam.posts.isEmpty())
            assertTrue(
                seam
                    .outputs()
                    .single()
                    .obj("item")
                    .text("output")
                    .contains("invalid", ignoreCase = true),
            )
        }

    @Test
    fun `accepted run is not announced as complete and unrelated history is ignored`() =
        runTest {
            val seam = Seam(output = "WORK_STARTED #17 kind=agent (research)")
            seam.acceptRun()
            seam.adapter.handle(created("continuation"))
            seam.adapter.handle(done(responseId = "continuation"))
            seam.adapter.updateRuns(
                listOf(
                    run(17, "running", ""),
                    run(99, "done", "old unrelated result"),
                ),
            )

            assertTrue(seam.notices().isEmpty())
            assertEquals(1, seam.creates().size)
            assertTrue(seam.transcripts.isEmpty())
        }

    @Test
    fun `terminal server result is announced once only after continuation and audio are idle`() =
        runTest {
            val seam = Seam(output = "WORK_STARTED #17 kind=agent (research)")
            seam.acceptRun()
            seam.adapter.updateRuns(listOf(run(17, "done", "Server found three papers")))
            assertTrue(seam.notices().isEmpty())
            seam.adapter.handle(created("continuation"))
            seam.adapter.handle(event("output_audio_buffer.started"))
            seam.adapter.handle(done(responseId = "continuation"))
            assertTrue(seam.notices().isEmpty())
            seam.adapter.handle(event("output_audio_buffer.stopped"))
            seam.adapter.updateRuns(listOf(run(17, "done", "Server found three papers")))

            val notice = seam.notices().single().obj("item")
            assertEquals("user", notice.text("role"))
            assertTrue(notice.toString().contains("Server found three papers"))
            assertTrue(notice.toString().contains("status 'done'"))
            assertEquals(2, seam.creates().size)
        }

    @Test
    fun `new adapter never announces historical runs without its own accepted sentinel`() =
        runTest {
            val seam = Seam()
            seam.adapter.updateRuns(listOf(run(17, "done", "historical")))
            assertTrue(seam.sent.isEmpty())
        }

    @Test
    fun `terminal output may watch a server announced follow on run`() =
        runTest {
            val seam = Seam(output = "WORK_STARTED #17 kind=agent (research)")
            seam.acceptRun()
            seam.adapter.handle(created("continuation"))
            seam.adapter.handle(done(responseId = "continuation"))
            seam.adapter.updateRuns(listOf(run(17, "done", "WORK_STARTED #18 kind=skill (report)")))
            seam.adapter.handle(created("notice"))
            seam.adapter.handle(done(responseId = "notice"))
            seam.adapter.updateRuns(listOf(run(18, "done", "Report saved")))

            assertEquals(2, seam.notices().size)
            assertTrue(
                seam
                    .notices()
                    .last()
                    .toString()
                    .contains("Report saved"),
            )
        }

    @Test
    fun `interrupt cancels provider speech without cancelling accepted server work`() =
        runTest {
            val seam = Seam(output = "WORK_STARTED #17 kind=agent (research)")
            seam.adapter.handle(created())
            seam.adapter.handle(tool())
            seam.adapter.handle(event("input_audio_buffer.speech_started"))
            seam.adapter.handle(done(call("call-1")))
            seam.adapter.handle(event("input_audio_buffer.speech_stopped"))
            seam.adapter.handle(created("next"))
            seam.adapter.handle(done(responseId = "next"))
            seam.adapter.updateRuns(listOf(run(17, "done", "Work completed on the server")))

            assertTrue(seam.sent.any { it.text("type") == "response.cancel" })
            assertTrue(seam.sent.any { it.text("type") == "output_audio_buffer.clear" })
            assertEquals(listOf("/tool"), seam.posts.map { it.first })
            assertFalse(seam.posts.any { it.second.text("name") == "stop_work" })
            assertEquals(1, seam.notices().size)
        }

    @Test
    fun `idle interrupt sends no provider request and close drops all local continuations`() =
        runTest {
            val seam = Seam(output = "WORK_STARTED #17 kind=agent (research)")
            seam.adapter.interrupt()
            assertTrue(seam.sent.isEmpty())
            seam.adapter.handle(created())
            seam.adapter.handle(tool())
            seam.adapter.close()
            val sentAtClose = seam.sent.size
            seam.adapter.handle(done(call("call-1")))
            seam.adapter.handle(tool("late"))
            seam.adapter.updateRuns(listOf(run(17, "done", "Still server owned")))
            seam.adapter.interrupt()
            seam.adapter.close()

            assertEquals(sentAtClose, seam.sent.size)
            assertEquals(listOf("/tool"), seam.posts.map { it.first })
            assertTrue(seam.outputs().isEmpty())
            assertTrue(seam.notices().isEmpty())
        }

    @Test
    fun `native errors never expose credential or raw server message`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(json("""{"type":"error","error":{"message":"Bearer secret-server-body"}}"""))
            assertEquals(listOf("Realtime provider reported an error."), seam.errors)
        }

    private class Seam(
        output: String = "server output",
        failTool: Boolean = false,
    ) {
        val posts = mutableListOf<Pair<String, JsonObject>>()
        val sent = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()
        val transcripts = mutableListOf<Triple<String, String, Boolean>>()
        val adapter =
            TalkRealtimeAdapter(
                post = { path, body ->
                    posts += path to body
                    if (failTool) error("secret-server-body")
                    JsonObject(mapOf("ok" to JsonPrimitive(true), "output" to JsonPrimitive(output)))
                },
                send = {
                    sent += it
                    true
                },
                onTranscript = { role, text, final -> transcripts += Triple(role, text, final) },
                onState = {},
                onError = { errors += it },
            )

        suspend fun acceptRun() {
            adapter.handle(created())
            adapter.handle(tool())
            adapter.handle(done(call("call-1")))
        }

        fun creates() = sent.filter { it.text("type") == "response.create" }

        fun outputs() = sent.filter { it.obj("item").text("type") == "function_call_output" }

        fun notices() = sent.filter { it.obj("item").text("type") == "message" }
    }

    companion object {
        private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

        private fun event(type: String) = json("""{"type":"$type"}""")

        private fun created(id: String = "response-1") = json("""{"type":"response.created","response":{"id":"$id"}}""")

        private fun tool(
            id: String = "call-1",
            arguments: String = "{}",
        ) = JsonObject(
            mapOf(
                "type" to JsonPrimitive("response.function_call_arguments.done"),
                "response_id" to JsonPrimitive("response-1"),
                "call_id" to JsonPrimitive(id),
                "name" to JsonPrimitive("delegate_task"),
                "arguments" to JsonPrimitive(arguments),
            ),
        )

        private fun call(
            id: String,
            arguments: String = "{}",
        ) = JsonObject(
            mapOf(
                "type" to JsonPrimitive("function_call"),
                "id" to JsonPrimitive("item-$id"),
                "call_id" to JsonPrimitive(id),
                "name" to JsonPrimitive("delegate_task"),
                "arguments" to JsonPrimitive(arguments),
            ),
        )

        private fun done(
            vararg output: JsonObject,
            responseId: String = "response-1",
        ) = JsonObject(
            mapOf(
                "type" to JsonPrimitive("response.done"),
                "response" to
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(responseId),
                            "status" to JsonPrimitive("completed"),
                            "output" to JsonArray(output.toList()),
                        ),
                    ),
            ),
        )

        private fun run(
            id: Int,
            status: String,
            output: String,
        ) = JsonObject(
            mapOf(
                "runId" to JsonPrimitive(id),
                "kind" to JsonPrimitive("agent"),
                "status" to JsonPrimitive(status),
                "output" to JsonPrimitive(output),
            ),
        )

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()

        private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    }
}
