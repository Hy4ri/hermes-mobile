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

class TalkBoundAdapterTest {
    @Test
    fun `duplicate tool events dispatch once and wait for response done`() =
        runTest {
            val seam = Seam()
            seam.start()
            seam.adapter.handle(tool())
            seam.adapter.handle(tool())
            assertEquals(1, seam.posts.count { it.first == "/tool" })
            assertTrue(seam.outputs().isEmpty())
            seam.adapter.handle(done(call("call-1")))
            seam.adapter.handle(tool())
            seam.adapter.handle(done(call("call-1")))
            assertEquals(1, seam.outputs().size)
            // OpenAI item IDs reject the full UUID+prefix (37 chars).
            assertTrue(
                seam
                    .outputs()
                    .single()
                    .obj("item")
                    .text("id")
                    .length <= 32,
            )
            assertEquals(1, seam.posts.count { it.first == "/tool" })
            assertEquals(
                "server output",
                seam
                    .outputs()
                    .single()
                    .obj("item")
                    .text("output"),
            )
            assertEquals(
                "response-1",
                seam
                    .creates()
                    .last()
                    .obj("response")
                    .obj("metadata")
                    .text("talk_previous_response_id"),
            )
        }

    @Test
    fun `unlinked tool and response cannot dispatch`() =
        runTest {
            val seam = Seam()
            seam.adapter.handle(tool())
            seam.adapter.handle(json("""{"type":"response.created","response":{"id":"response-1","metadata":{}}}"""))
            seam.adapter.handle(tool())
            assertTrue(seam.posts.isEmpty())
            assertTrue(seam.outputs().isEmpty())
            assertTrue(seam.errors.isNotEmpty())
        }

    @Test
    fun `done before arguments waits for every declared call and emits declared order`() =
        runTest {
            val seam = Seam()
            seam.start()
            seam.adapter.handle(done(call("call-2"), call("call-1")))
            assertTrue(seam.outputs().isEmpty())
            seam.adapter.handle(tool("call-1"))
            assertTrue(seam.outputs().isEmpty())
            seam.adapter.handle(tool("call-2"))
            assertEquals(listOf("call-2", "call-1"), seam.outputs().map { it.obj("item").text("call_id") })
            val doneIndex = seam.posts.indexOfFirst { it.second.text("kind") == "response.done" }
            assertTrue(doneIndex < seam.posts.indexOfFirst { it.first == "/tool" })
        }

    @Test
    fun `committed and ASR order both stage exact input before requesting response`() =
        runTest {
            for (commitFirst in listOf(true, false)) {
                val seam = Seam()
                if (commitFirst) seam.adapter.handle(committed()) else seam.adapter.handle(asr())
                assertTrue(seam.creates().isEmpty())
                if (commitFirst) seam.adapter.handle(asr()) else seam.adapter.handle(committed())
                seam.adapter.handle(asr())
                seam.adapter.handle(committed())
                assertEquals(1, seam.posts.count { it.second.text("kind") == "input.final" })
                assertEquals(1, seam.creates().size)
                val staged = seam.posts.first { it.second.text("kind") == "input.final" }.second
                assertEquals("exact operator words", staged.text("text"))
                assertEquals("voice", staged.text("input_type"))
                val response = seam.creates().single().obj("response")
                assertEquals("input-1", response.obj("metadata").text("talk_input_id"))
                assertEquals("interaction-1", response.obj("metadata").text("talk_interaction_id"))
                assertEquals("", response.obj("metadata").text("talk_previous_response_id"))
                assertEquals("input-1", (response["input"] as JsonArray).single().let { it as JsonObject }.text("id"))
            }
        }

    @Test
    fun `model claim is only response text not canonical task completion`() =
        runTest {
            val seam = Seam()
            seam.start()
            seam.adapter.handle(
                json(
                    """{"type":"response.output_text.done","response_id":"response-1","item_id":"answer-1",
                    "text":"The canonical task is done."}""",
                ),
            )
            seam.adapter.handle(
                done(
                    json(
                        """{"type":"message","id":"answer-1",
                        "content":[{"type":"output_text","text":"The canonical task is done."}]}""",
                    ),
                ),
            )
            assertEquals(1, seam.posts.count { it.second.text("kind") == "response.final" })
            assertEquals(1, seam.posts.count { it.second.text("kind") == "interaction.settle" })
            assertTrue(seam.posts.all { it.first == "/event" || it.first == "/state" })
            assertFalse(seam.states.any { it == "completed" || it == "done" })
        }

    @Test
    fun `uncertain tool is never retried and does not continue`() =
        runTest {
            val seam = Seam(failTool = true)
            seam.start()
            seam.adapter.handle(tool())
            seam.adapter.handle(tool())
            seam.adapter.handle(done(call("call-1")))
            assertEquals(1, seam.posts.count { it.first == "/tool" })
            assertTrue(seam.outputs().isEmpty())
            assertEquals(1, seam.creates().size)
            assertTrue(seam.posts.any { it.second.text("kind") == "interaction.incomplete" })
        }

    @Test
    fun `malformed arguments and mismatched receipt fail closed`() =
        runTest {
            val seam = Seam()
            seam.start()
            seam.adapter.handle(
                json(
                    """{"type":"response.function_call_arguments.done","response_id":"response-1",
                    "call_id":"call-1","name":"delegate_task","arguments":"[1]"}""",
                ),
            )
            assertEquals(0, seam.posts.count { it.first == "/tool" })
            val badReceipt = Seam(badStageReceipt = true)
            badReceipt.adapter.handle(committed())
            badReceipt.adapter.handle(asr())
            assertTrue(badReceipt.creates().isEmpty())
        }

    @Test
    fun `selection intent never activates a new target`() =
        runTest {
            val seam = Seam(selection = true)
            seam.start()
            seam.adapter.handle(tool(name = "switch_target"))
            seam.adapter.handle(done(call("call-1", "switch_target")))
            val output =
                seam
                    .outputs()
                    .single()
                    .obj("item")
                    .text("output")
            assertTrue(output.contains("No target change"))
            assertFalse(seam.posts.any { it.first == "/switch" })
        }

    @Test
    fun `presentation is isolated and native delivery stays unknown`() =
        runTest {
            val seam = Seam()
            seam.adapter.refresh(json("""{"announcements":[{"event_id":"event-1"}]}"""))
            val speech = seam.posts.single { it.first == "/speech" }.second
            assertEquals(JsonPrimitive(false), speech["playback_supported"])
            assertEquals(JsonPrimitive(1), speech["presentation_protocol"])
            val response = seam.creates().single().obj("response")
            assertEquals("none", response.text("conversation"))
            assertEquals("none", response.text("tool_choice"))
            assertEquals(JsonArray(emptyList()), response["tools"])
            seam.adapter.handle(
                json(
                    """{"type":"response.created","response":{"id":"summary-1",
                    "metadata":{"talk_presentation_id":"attempt-1","talk_event_id":"event-1"}}}""",
                ),
            )
            seam.adapter.handle(
                json(
                    """{"type":"response.function_call_arguments.done","response_id":"summary-1",
                    "call_id":"evil","name":"delegate_task","arguments":"{}"}""",
                ),
            )
            seam.adapter.handle(json("""{"type":"output_audio_buffer.started","response_id":"summary-1"}"""))
            seam.adapter.handle(
                json(
                    """{"type":"response.done","response":{"id":"summary-1","status":"completed","output":[]}}""",
                ),
            )
            seam.adapter.interrupt()
            assertEquals(
                json("""{"type":"output_audio_buffer.clear"}"""),
                seam.sent.single { it.text("type") == "output_audio_buffer.clear" },
            )
            assertFalse(seam.posts.any { it.first == "/tool" })
            assertEquals(
                listOf("submitting", "context_submitted", "unknown"),
                seam.posts.filter { it.first == "/speech/receipt" }.map { it.second.text("state") },
            )
        }

    @Test
    fun `interrupt cancels presentation but not ordinary response or task`() =
        runTest {
            val seam = Seam()
            seam.start()
            seam.adapter.interrupt()
            assertFalse(seam.sent.any { it.text("type") == "response.cancel" })
            assertFalse(seam.posts.any { it.first == "/close" || it.first == "/tool" })
            seam.adapter.close()
            seam.adapter.handle(tool())
            assertEquals(1, seam.posts.count { it.first == "/close" })
            assertFalse(seam.posts.any { it.first == "/tool" })
        }

    @Test
    fun `speech interruption retires only synthetic presentation and late tools stay inert`() =
        runTest {
            val seam = Seam()
            seam.adapter.refresh(json("""{"announcements":[{"event_id":"event-1"}]}"""))
            seam.adapter.handle(
                json(
                    """{"type":"response.created","response":{"id":"summary-1",
                    "metadata":{"talk_presentation_id":"attempt-1","talk_event_id":"event-1"}}}""",
                ),
            )
            seam.adapter.handle(json("""{"type":"input_audio_buffer.speech_started"}"""))
            assertEquals(
                json("""{"type":"response.cancel","response_id":"summary-1"}"""),
                seam.sent.single { it.text("type") == "response.cancel" },
            )
            val terminal = seam.posts.last { it.first == "/speech/receipt" }.second
            assertEquals("event-1", terminal.text("event_id"))
            assertEquals("attempt-1", terminal.text("attempt_id"))
            assertEquals("summary-1", terminal.text("response_id"))
            assertEquals("unknown", terminal.text("state"))
            seam.adapter.handle(
                json(
                    """{"type":"response.function_call_arguments.done","response_id":"summary-1",
                    "call_id":"late-tool","name":"delegate_task","arguments":"{}"}""",
                ),
            )
            assertFalse(seam.posts.any { it.first == "/tool" || it.first == "/close" })
            assertTrue(seam.outputs().isEmpty())
            assertEquals("listening", seam.states.last())
        }

    @Test
    fun `barge in cancels old speech without cancelling work and accepts next input`() =
        runTest {
            val seam = Seam()
            seam.start()
            seam.adapter.handle(json("""{"type":"output_audio_buffer.started","response_id":"response-1"}"""))
            seam.adapter.handle(json("""{"type":"input_audio_buffer.speech_started"}"""))
            assertTrue(
                seam.sent.any {
                    it.text("type") == "response.cancel" && it.text("response_id") == "response-1"
                },
            )
            assertTrue(seam.sent.any { it.text("type") == "output_audio_buffer.clear" })
            assertFalse(seam.posts.any { it.first == "/tool" || it.first == "/text/input" || it.first == "/close" })
            seam.adapter.handle(json("""{"type":"error","error":{"message":"No active response to cancel"}}"""))
            assertTrue(seam.errors.isEmpty())
        }

    private class Seam(
        val failTool: Boolean = false,
        val badStageReceipt: Boolean = false,
        val selection: Boolean = false,
    ) {
        val posts = mutableListOf<Pair<String, JsonObject>>()
        val sent = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()
        val states = mutableListOf<String>()
        val adapter =
            TalkBoundAdapter(
                post = { path, body ->
                    posts += path to body
                    when (path) {
                        "/event" -> {
                            JsonObject(
                                mapOf(
                                    "ok" to JsonPrimitive(true),
                                    "interaction_id" to JsonPrimitive("interaction-1"),
                                    "input_id" to JsonPrimitive(if (badStageReceipt) "wrong" else "input-1"),
                                ),
                            )
                        }

                        "/tool" -> {
                            if (failTool) error("Uncertain transport result")
                            if (selection) {
                                json("""{"ok":true,"output":"selection intent","selection":{"target_id":"other"}}""")
                            } else {
                                json("""{"ok":true,"output":"server output"}""")
                            }
                        }

                        "/speech" -> {
                            json(
                                """{"ok":true,"speak":true,"event_id":"event-1","attempt_id":"attempt-1",
                            "response":{"conversation":"none","tools":[],"tool_choice":"none","input":[],
                            "metadata":{"talk_presentation_id":"attempt-1","talk_event_id":"event-1"}}}""",
                            )
                        }

                        "/speech/receipt" -> {
                            JsonObject(mapOf("ok" to JsonPrimitive(true), "state" to body.getValue("state")))
                        }

                        else -> {
                            json("""{"ok":true,"announcements":[]}""")
                        }
                    }
                },
                send = {
                    sent += it
                    true
                },
                onTranscript = { _, _, _ -> },
                onState = { states += it },
                onError = { errors += it },
            )

        suspend fun start() {
            adapter.handle(committed())
            adapter.handle(asr())
            val metadata = creates().single().obj("response").obj("metadata")
            adapter.handle(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("response.created"),
                        "response" to JsonObject(mapOf("id" to JsonPrimitive("response-1"), "metadata" to metadata)),
                    ),
                ),
            )
        }

        fun creates() = sent.filter { it.text("type") == "response.create" }

        fun outputs() = sent.filter { it.obj("item").text("type") == "function_call_output" }
    }

    companion object {
        private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

        private fun committed() = json("""{"type":"input_audio_buffer.committed","item_id":"input-1"}""")

        private fun asr() =
            json(
                """{"type":"conversation.item.input_audio_transcription.completed","item_id":"input-1",
                "transcript":"exact operator words"}""",
            )

        private fun tool(
            id: String = "call-1",
            name: String = "delegate_task",
        ) = json(
            """{"type":"response.function_call_arguments.done","response_id":"response-1",
                "call_id":"$id","name":"$name","arguments":"{}"}""",
        )

        private fun call(
            id: String,
            name: String = "delegate_task",
        ) = json(
            """{"type":"function_call","id":"item-$id","call_id":"$id","name":"$name","arguments":"{}"}""",
        )

        private fun done(vararg output: JsonObject) =
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("response.done"),
                    "response" to
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("response-1"),
                                "status" to JsonPrimitive("completed"),
                                "output" to JsonArray(output.toList()),
                            ),
                        ),
                ),
            )

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()

        private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
    }
}
