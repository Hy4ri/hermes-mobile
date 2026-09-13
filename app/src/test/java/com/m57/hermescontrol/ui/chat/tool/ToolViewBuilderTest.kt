package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolViewBuilderTest {
    private fun parse(json: String): JsonObject? = Json.parseToJsonElement(json) as? JsonObject

    private fun build(
        tool: String,
        args: String,
        result: String,
        isError: Boolean = false,
        running: Boolean = false,
    ) = ToolViewBuilder.build(tool, parse(args), parse(result), isError, running)

    // ── status heuristics ────────────────────────────────────────────────

    @Test
    fun `terminal success with exit zero is success`() {
        val view = build("terminal", """{"command":"ls -la"}""", """{"output":"wiring.tsx","exit_code":0}""")

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertNull(view.error)
        assertEquals("wiring.tsx", view.stdout)
        assertEquals(0, view.exitCode)
    }

    @Test
    fun `terminal non-zero exit without output is an error`() {
        val view = build("terminal", """{"command":"exit 1"}""", """{"exit_code":1}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("Command failed with exit code 1.", view.error)
    }

    @Test
    fun `terminal non-zero exit with output is not an error`() {
        val view = build("terminal", """{"command":"grep foo bar"}""", """{"exit_code":1,"output":"nothing"}""")

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertNull(view.error)
        assertEquals("nothing", view.stdout)
    }

    @Test
    fun `explicit success wins over stale isError envelope`() {
        val view = build("memory", """{"action":"add","target":"user"}""", """{"success":true}""", isError = true)

        assertEquals(ToolViewStatus.SUCCESS, view.status)
    }

    @Test
    fun `rejected memory write is a warning not an error`() {
        val view = build("memory", """{"action":"add"}""", """{"success":false,"message":"over budget"}""")

        assertEquals(ToolViewStatus.WARNING, view.status)
        assertEquals("over budget", view.error)
    }

    @Test
    fun `running tool gets running status and pending title`() {
        val view = build("terminal", """{"command":"sleep 10"}""", """{}""", running = true)

        assertEquals(ToolViewStatus.RUNNING, view.status)
        assertTrue(view.title.startsWith("Running"))
    }

    // ── titles / subtitles ───────────────────────────────────────────────

    @Test
    fun `read_file title names the target with line range`() {
        val view =
            build("read_file", """{"path":"/repo/src/a.kt","offset":10,"limit":5}""", """{"content":"10|a\n11|b"}""")

        assertEquals("Read a.kt L10-14", view.title)
        assertEquals("/repo/src/a.kt", view.subtitle)
    }

    @Test
    fun `terminal title uses summarized command`() {
        val view =
            build(
                "terminal",
                """{"command":"cd /tmp && sleep 70 2>&1 | tail -5; echo \"x_exit=${'$'}{PIPESTATUS[0]}\""}""",
                """{"output":"done"}""",
            )

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertTrue(view.title, view.title.startsWith("Ran "))
        assertTrue(view.title, view.title.contains("sleep 70"))
        assertFalse(view.title.contains("tail"))
    }

    @Test
    fun `web_search parses hits and count`() {
        val view =
            build(
                "web_search",
                """{"search_term":"cats"}""",
                """{"data":{"web":[
                    {"title":"Cat Facts","url":"https://cats.example/1","description":"all about cats"},
                    {"title":"More Cats","url":"https://cats.example/2","description":"even more"},
                    {"title":"Cat Videos","url":"https://cats.example/3","description":"videos"}
                ]}}""",
            )

        assertEquals("Searched \"cats\"", view.title)
        assertEquals("3 results", view.countLabel)
        assertEquals(3, view.searchHits?.size)
        assertEquals("Cat Facts", view.searchHits?.first()?.title)
        assertEquals("cats", view.searchQuery)
        assertEquals("Search results", view.detailLabel)
    }

    @Test
    fun `patch extracts inline diff with stats`() {
        val view =
            build(
                "patch",
                """{"path":"/repo/src/wiring.tsx"}""",
                """{"inline_diff":"--- a/wiring.tsx\n+++ b/wiring.tsx\n@@ -1,1 +1,1 @@\n-old\n+new"}""",
            )

        assertNotNull(view.inlineDiff)
        assertEquals("/repo/src/wiring.tsx", view.diffPath)
        assertEquals(1, view.diffStats?.added)
        assertEquals(1, view.diffStats?.removed)
        assertEquals("wiring.tsx", view.title)
    }

    @Test
    fun `browser_snapshot subtitle summarizes accessibility stats`() {
        val view =
            build(
                "browser_snapshot",
                """{}""",
                """{"snapshot":"button \"Save\"\nbutton \"Cancel\"\nlink \"Docs\"\ntextbox \"Name\""}""",
            )

        assertTrue(view.subtitle, view.subtitle.contains("2 buttons"))
        assertTrue(view.subtitle.contains("1 links"))
        assertTrue(view.subtitle.contains("Top controls: Save, Cancel"))
    }

    // ── mobile-specific tools ────────────────────────────────────────────

    @Test
    fun `fact_store added fact gets clean summary and detail`() {
        val view =
            build(
                "fact_store",
                """{"action":"add"}""",
                """{"status":"added","fact_id":42}""",
            )

        assertEquals("Fact added (ID: 42)", view.subtitle)
    }

    @Test
    fun `fact_store list renders facts with trust scores`() {
        val view =
            build(
                "fact_store",
                """{"action":"search","query":"x"}""",
                """{"count":1,"results":[{"fact_id":7,"content":"user likes tea",""" +
                    """"category":"user_pref","trust_score":0.8}]}""",
            )

        assertEquals("1 facts (search: x)", view.subtitle)
        assertTrue(view.detail, view.detail.contains("#7  user likes tea"))
        assertTrue(view.detail.contains("trust: 0.80"))
    }

    @Test
    fun `todo gets count subtitle and markers`() {
        val view =
            build(
                "todo",
                """{"action":"create"}""",
                """{"summary":{"total":2,"pending":1,"completed":1},"todos":[
                    {"id":"1","content":"one","status":"pending"},
                    {"id":"2","content":"two","status":"completed"}
                ]}""",
            )

        assertEquals("2 items (1 pending, 1 completed)", view.subtitle)
        assertTrue(view.detail, view.detail.contains("[ ] 1. one"))
        assertTrue(view.detail.contains("[x] 2. two"))
    }

    @Test
    fun `cronjob list subtitle counts jobs`() {
        val view =
            build(
                "cronjob",
                """{"action":"list"}""",
                """{"jobs":[{"name":"daily","schedule":"0 9 * * *"},{"name":"hourly","schedule":"0 * * * *"}]}""",
            )

        assertEquals("2 cron jobs", view.subtitle)
        assertTrue(view.detail, view.detail.contains("- daily · 0 9 * * *"))
    }

    @Test
    fun `session_search discover mode`() {
        val view =
            build(
                "session_search",
                """{"query":"auth"}""",
                """{"mode":"discover","count":2,"results":[{"title":"Auth refactor",""" +
                    """"when":"2026-07-01","snippet":"..."}]}""",
            )

        assertTrue(view.subtitle, view.subtitle.startsWith("2 sessions"))
        assertTrue(view.detail, view.detail.contains("Auth refactor"))
    }

    @Test
    fun `read_terminal custom detail`() {
        val view =
            build(
                "read_terminal",
                """{"session_id":"s1"}""",
                """{"total_lines":50,"start":1,"end":20,"text":"line one","cursor_row":5}""",
            )

        assertEquals("Lines 1-20 of 50, cursor at row 5", view.subtitle)
        assertTrue(view.detail, view.detail.contains("━━━ Terminal ━━━"))
        assertTrue(view.detail.contains("line one"))
    }

    // ── unknown tools fall back to the generic engine ────────────────────

    @Test
    fun `unknown tool gets a heuristic summary not raw json`() {
        val view =
            build(
                "weird_tool",
                """{}""",
                """{"result":{"data":{"title":"Build report","completed":true}}}""",
            )

        assertEquals("Weird Tool", view.title)
        assertTrue(view.detail, view.detail.contains("Title: Build report"))
        assertTrue(view.detail.contains("Completed: true"))
        assertFalse(view.detail.contains("\"data\""))
    }

    @Test
    fun `unknown tool error surfaces through the generic engine`() {
        val view =
            build(
                "weird_tool",
                """{}""",
                """{"success":false,"error":{"message":"Permission denied"}}""",
            )

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertTrue(view.error?.contains("Permission denied") == true)
    }

    // ── name aliases ──────────────────────────────────────────────────────

    @Test
    fun `process_manage alias activates ProcessRenderer`() {
        val view =
            build(
                "process_manage",
                """{"action":"list"}""",
                """{"processes":[{"pid":123,"command":"sleep 100","status":"running"}]}""",
            )

        assertEquals("list", view.subtitle)
        assertTrue(view.detail.contains("sleep 100"))
    }

    @Test
    fun `todo_list alias activates TodoRenderer`() {
        val view =
            build(
                "todo_list",
                """{"action":"create"}""",
                """{"summary":{"total":1,"pending":1,"completed":0},"todos":""" +
                    """[{"id":"1","content":"write tests","status":"pending"}]}""",
            )

        assertEquals("1 item (1 pending)", view.subtitle)
        assertTrue(view.detail.contains("[ ] 1. write tests"))
    }

    @Test
    fun `cronjob_manage alias activates CronjobRenderer`() {
        val view =
            build(
                "cronjob_manage",
                """{"action":"list"}""",
                """{"jobs":[{"name":"backup","schedule":"0 0 * * *"}]}""",
            )

        assertEquals("1 cron job", view.subtitle)
        assertTrue(view.detail.contains("- backup · 0 0 * * *"))
    }

    // ── new renderers ─────────────────────────────────────────────────────

    @Test
    fun `search_files dense format parses matches_text and count`() {
        val view =
            build(
                "search_files",
                """{"pattern":"fun connect","path":"app/src"}""",
                """{"total_count":2,"matches_text":"app/src/A.kt:10: fun connect()\napp/src/B.kt:20: fun connect()"}""",
            )

        assertEquals("Searched “fun connect” · 2 matches", view.title)
        assertEquals("app/src", view.subtitle)
        assertTrue(view.detail.contains("app/src/A.kt:10: fun connect()"))
        assertEquals("Matches", view.detailLabel)
    }

    @Test
    fun `search_files sparse array format formats hits`() {
        val view =
            build(
                "search_files",
                """{"pattern":"test","path":"."}""",
                """{"total_count":1,"matches":[{"path":"foo.kt","line":42,"content":"val test = 1"}]}""",
            )

        assertEquals("Searched “test” · 1 match", view.title)
        assertTrue(view.detail.contains("foo.kt:42: val test = 1"))
    }

    @Test
    fun `delegate_task handles joined spawn with status markers`() {
        val view =
            build(
                "delegate_task",
                """{"action":"spawn","tasks":[{"goal":"Research architecture"},{"goal":"Write code"}]}""",
                """{"results":[{"task_index":0,"status":"completed","model":"claude-3-7","summary":"Researched OK",""" +
                    """"duration_seconds":12.5},{"task_index":1,"status":"failed","error":"Build broke",""" +
                    """"duration_seconds":4.0}]}""",
            )

        assertEquals("Delegated 2 tasks · 1 failed", view.title)
        assertEquals("Research architecture", view.subtitle)
        assertTrue(view.detail.contains("[x] Research architecture"))
        assertTrue(view.detail.contains("claude-3-7"))
        assertTrue(view.detail.contains("[!] Write code"))
        assertTrue(view.detail.contains("Build broke"))
    }

    @Test
    fun `delegate_task handles list action`() {
        val view =
            build(
                "delegate_task",
                """{"action":"list"}""",
                """{"count":1,"subagents":[{"goal":"Background indexing","status":"running",""" +
                    """"running_seconds":35.0}]}""",
            )

        assertEquals("1 subagent", view.title)
        assertTrue(view.detail.contains("- [running] Background indexing · 35s"))
    }

    @Test
    fun `text_to_speech formats provider and audio path`() {
        val view =
            build(
                "text_to_speech",
                """{"text":"Hello world"}""",
                """{"provider":"edge","chunk_count":1,"file_path":"/tmp/audio.mp3"}""",
            )

        assertEquals("Generated speech", view.title)
        assertEquals("edge", view.subtitle)
        assertTrue(view.detail.contains("“Hello world”"))
        assertTrue(view.detail.contains("Saved to /tmp/audio.mp3"))
    }

    @Test
    fun `browser action renderers format scroll, back, vision and console`() {
        val scroll = build("browser_scroll", """{"direction":"down"}""", """{"scrolled":"down"}""")
        assertEquals("Scrolled down", scroll.title)

        val back = build("browser_back", """{}""", """{"url":"https://example.com/docs"}""")
        assertEquals("Went back to example.com/docs", back.title)

        val vision =
            build("browser_vision", """{"question":"Is there a login button?"}""", """{"analysis":"Yes, top right"}""")
        assertEquals("Analyzed page", vision.title)
        assertEquals("Is there a login button?", vision.subtitle)
        assertEquals("Yes, top right", vision.detail)

        val console =
            build(
                "browser_console",
                """{}""",
                """{"total_messages":5,"total_errors":1,"js_errors":[{"message":"Uncaught TypeError"}]}""",
            )
        assertEquals("Console: 5 messages, 1 error", console.title)
        assertTrue(console.detail.contains("[error] Uncaught TypeError"))
    }

    @Test
    fun `output is clamped to maximum display chars`() {
        val longOutput = "a".repeat(25_000)
        val view = build("terminal", """{"command":"dump"}""", """{"output":"$longOutput"}""")

        val stdout = view.stdout
        assertNotNull(stdout)
        assertTrue(stdout!!.length < 25_000)
        assertTrue(stdout.contains("more characters truncated"))
    }
}
