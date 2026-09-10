package com.m57.hermescontrol.ui.chat.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for [ToolViewBuilder] — pinned observable behavior
 * of the tool-display engine, written against the pre-refactor monolith so
 * the renderer/registry split can be proven behavior-preserving.
 *
 * Every expected value here was produced by the original single-file
 * implementation. If one of these needs to change, that is an intentional
 * behavior change and must be called out in review — not a refactor detail.
 */
class ToolViewCharacterizationTest {
    private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

    private fun build(
        tool: String,
        args: String? = null,
        result: String? = null,
        isError: Boolean = false,
        running: Boolean = false,
    ) = ToolViewBuilder.build(tool, args?.let(::json), result?.let(::json), isError, running)

    // ── terminal: streams, exit codes, command line ──────────────────────

    @Test
    fun `terminal merged output populates stdout and empty stderr`() {
        val view = build("terminal", """{"command":"ls -la"}""", """{"output":"file1\nfile2","exit_code":0}""")

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertEquals("Ran ls -la", view.title)
        assertEquals("file1", view.subtitle)
        assertEquals("file1\nfile2", view.detail)
        assertEquals("file1\nfile2", view.stdout)
        assertEquals("", view.stderr)
        assertEquals(0, view.exitCode)
        assertEquals("ls -la", view.terminalCommand)
    }

    @Test
    fun `terminal split streams keep stdout and stderr separate`() {
        val view = build("terminal", """{"command":"make"}""", """{"stdout":"built","stderr":"warn: x","exit_code":0}""")

        assertEquals("built", view.stdout)
        assertEquals("warn: x", view.stderr)
    }

    @Test
    fun `terminal non-zero exit with only stderr output is not an error`() {
        val view = build("terminal", """{"command":"grep foo"}""", """{"exit_code":1,"stderr":"grep: warning"}""")

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertNull(view.error)
        assertEquals("", view.stdout)
        assertEquals("grep: warning", view.stderr)
        assertEquals(1, view.exitCode)
    }

    @Test
    fun `terminal non-zero exit with no output is an error with generic title`() {
        val view = build("terminal", """{"command":"exit 2"}""", """{"exit_code":2}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("Command failed with exit code 2.", view.error)
        assertEquals("Terminal", view.title)
        assertEquals("Command failed with exit code 2.", view.subtitle)
        assertEquals("Command failed with exit code 2.", view.detail)
        assertNull(view.countLabel)
    }

    @Test
    fun `terminal exit code accepts float and numeric string`() {
        assertEquals(0, build("terminal", result = """{"output":"ok","exit_code":0.0}""").exitCode)
        assertEquals(0, build("terminal", result = """{"output":"ok","exit_code":"0.0"}""").exitCode)
        assertEquals(3, build("terminal", result = """{"output":"ok","exit_code":"3"}""").exitCode)
    }

    @Test
    fun `terminal title collapses plumbing to the main command`() {
        val view =
            build(
                "terminal",
                """{"command":"cd /tmp && sleep 70 2>&1 | tail -5; echo \"x_exit=${'$'}{PIPESTATUS[0]}\""}""",
                """{"output":"done"}""",
            )

        assertEquals("Ran sleep 70", view.title)
    }

    @Test
    fun `terminal with no output has empty subtitle and detail`() {
        val view = build("terminal", """{"command":"true"}""", """{"exit_code":0,"output":""}""")

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertEquals("", view.subtitle)
        assertEquals("", view.detail)
        assertNull(view.stdout)
        assertNull(view.stderr)
    }

    @Test
    fun `terminal lines array joins into detail`() {
        val view = build("terminal", """{"command":"cat x"}""", """{"lines":["a","b"],"exit_code":0}""")

        assertEquals("a\nb", view.detail)
        assertEquals("a", view.subtitle)
    }

    @Test
    fun `execute_code renders streams but no exit code or terminal command`() {
        val view = build("execute_code", """{"code":"print(1)"}""", """{"output":"1","exit_code":0}""")

        assertEquals("Ran code print(1)", view.title)
        assertEquals("1", view.stdout)
        assertNull(view.exitCode)
        assertNull(view.terminalCommand)
    }

    @Test
    fun `running terminal has running status pending title and no streams`() {
        val view = build("terminal", """{"command":"sleep 10"}""", running = true)

        assertEquals(ToolViewStatus.RUNNING, view.status)
        assertEquals("Running sleep 10", view.title)
        assertNull(view.stdout)
        assertNull(view.exitCode)
    }

    @Test
    fun `null result without running flag is still running status`() {
        val view = build("terminal", """{"command":"sleep 10"}""")

        assertEquals(ToolViewStatus.RUNNING, view.status)
        // Quirk pinned: title uses the done form because running=false.
        assertEquals("Ran sleep 10", view.title)
    }

    @Test
    fun `terminal error field beats useful output`() {
        val view = build("terminal", result = """{"error":"Invalid command: frobnicate"}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("Invalid command: frobnicate", view.error)
    }

    // ── status heuristics ────────────────────────────────────────────────

    @Test
    fun `explicit success true wins over isError envelope`() {
        val view = build("weird_tool", "{}", """{"success":true,"message":"done"}""", isError = true)

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertNull(view.error)
    }

    @Test
    fun `isError envelope surfaces extracted message`() {
        val view = build("weird_tool", "{}", """{"message":"boom"}""", isError = true)

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("Tool returned an error.", view.error)
    }

    @Test
    fun `isError with string result uses the string as error`() {
        val view = ToolViewBuilder.build("weird_tool", null, JsonPrimitive("everything broke"), isError = true)

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("everything broke", view.error)
    }

    @Test
    fun `success false without message gets canned error`() {
        val view = build("weird_tool", "{}", """{"success":false}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("Tool returned success=false.", view.error)
    }

    @Test
    fun `ok false with reason surfaces the reason`() {
        val view = build("weird_tool", "{}", """{"ok":false,"reason":"quota exceeded"}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("quota exceeded", view.error)
    }

    @Test
    fun `status failed string is an error`() {
        val view = build("weird_tool", "{}", """{"status":"failed"}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("Tool returned status \"failed\".", view.error)
    }

    @Test
    fun `nested error object surfaces through wrappers`() {
        val view = build("weird_tool", "{}", """{"data":{"error":{"message":"Permission denied"}}}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertEquals("Permission denied", view.error)
    }

    @Test
    fun `rejected memory write is a warning not an error`() {
        val view = build("memory", """{"action":"add"}""", """{"success":false,"message":"over budget"}""")

        assertEquals(ToolViewStatus.WARNING, view.status)
        assertEquals("over budget", view.error)
        assertEquals("Memory", view.title)
        // Error text and identical detail body are deduplicated.
        assertEquals("over budget", view.detail)
    }

    // ── file edits: diffs, stats, paths ──────────────────────────────────

    @Test
    fun `patch extracts inline diff with stats and empty detail`() {
        val view =
            build(
                "patch",
                """{"path":"/repo/src/wiring.tsx"}""",
                """{"inline_diff":"--- a/wiring.tsx\n+++ b/wiring.tsx\n@@ -1,2 +1,1 @@\n-old\n-older\n+new"}""",
            )

        assertEquals("wiring.tsx", view.title)
        assertEquals("/repo/src/wiring.tsx", view.subtitle)
        assertEquals("", view.detail)
        assertEquals("/repo/src/wiring.tsx", view.diffPath)
        assertEquals(DiffStats(added = 1, removed = 2), view.diffStats)
        assertTrue(view.inlineDiff!!.startsWith("--- a/wiring.tsx"))
    }

    @Test
    fun `inline diff strips ansi codes and review-diff chrome`() {
        val view =
            build(
                "edit_file",
                """{"path":"/x/y.kt"}""",
                """{"inline_diff":"\u001b[1m┊ review diff\n--- a/y.kt\n+++ b/y.kt\n+\u001b[32madded\u001b[0m"}""",
            )

        assertEquals("--- a/y.kt\n+++ b/y.kt\n+added", view.inlineDiff)
        assertEquals(DiffStats(added = 1, removed = 0), view.diffStats)
    }

    @Test
    fun `diff key works as fallback for inline_diff`() {
        val view = build("write_file", """{"path":"/a/b.txt"}""", """{"diff":"+hello"}""")

        assertEquals("+hello", view.inlineDiff)
    }

    @Test
    fun `file edit without path recovers html path from diff`() {
        val view = build("patch", "{}", """{"inline_diff":"--- a/index.html\n+++ b/index.html\n+<p>hi</p>"}""")

        assertEquals("index.html", view.title)
        assertEquals("index.html", view.diffPath)
    }

    @Test
    fun `write_file without diff shows message detail`() {
        val view = build("write_file", """{"path":"/a/b.txt"}""", """{"message":"Wrote 40 bytes"}""")

        assertEquals("b.txt", view.title)
        assertEquals("/a/b.txt", view.subtitle)
        assertEquals("Wrote 40 bytes", view.detail)
        assertNull(view.inlineDiff)
        assertNull(view.diffStats)
    }

    @Test
    fun `read_file shows content detail and line range title`() {
        val view =
            build("read_file", """{"path":"/repo/src/a.kt","offset":10,"limit":5}""", """{"content":"10|a\n11|b"}""")

        assertEquals("Read a.kt L10-14", view.title)
        assertEquals("/repo/src/a.kt", view.subtitle)
        assertEquals("10|a\n11|b", view.detail)
        assertNull(view.inlineDiff)
    }

    @Test
    fun `read_file running title uses reading verb`() {
        val view = build("read_file", """{"path":"/repo/src/a.kt"}""", running = true)

        assertEquals("Reading a.kt", view.title)
        assertEquals(ToolViewStatus.RUNNING, view.status)
    }

    // ── web search ───────────────────────────────────────────────────────

    @Test
    fun `web_search extracts hits count query and detail label`() {
        val view =
            build(
                "web_search",
                """{"search_term":"cats"}""",
                """{"data":{"web":[
                    {"title":"Cat Facts","url":"https://cats.example/1","description":"all about cats"},
                    {"title":"More Cats","url":"https://cats.example/2","description":"even more"}
                ]}}""",
            )

        assertEquals("Searched \"cats\"", view.title)
        assertEquals("Query: cats", view.subtitle)
        assertEquals("2 results", view.countLabel)
        assertEquals("cats", view.searchQuery)
        assertEquals("Search results", view.detailLabel)
        assertEquals(
            listOf(
                SearchHit("Cat Facts", "https://cats.example/1", "all about cats"),
                SearchHit("More Cats", "https://cats.example/2", "even more"),
            ),
            view.searchHits,
        )
    }

    @Test
    fun `web_search accepts alternate hit field names`() {
        val view =
            build(
                "web_search",
                """{"query":"dogs"}""",
                """{"results":[{"name":"Dog","href":"https://dogs.example","body":"woof"}]}""",
            )

        assertEquals(listOf(SearchHit("Dog", "https://dogs.example", "woof")), view.searchHits)
        assertEquals("dogs", view.searchQuery)
    }

    @Test
    fun `web_search error suppresses hits and count`() {
        val view = build("web_search", """{"search_term":"cats"}""", """{"error":"rate limited"}""")

        assertEquals(ToolViewStatus.ERROR, view.status)
        assertNull(view.searchHits)
        assertNull(view.countLabel)
        assertEquals("rate limited", view.subtitle)
        assertEquals("cats", view.searchQuery)
    }

    @Test
    fun `web_search running title quotes the query`() {
        val view = build("web_search", """{"search_term":"kotlin flow"}""", running = true)

        assertEquals("Searching \"kotlin flow\"", view.title)
    }

    // ── web extract / browser tools ──────────────────────────────────────

    @Test
    fun `web_extract titles hostname and strips trailing duration from content`() {
        val view =
            build(
                "web_extract",
                """{"urls":["https://ex.com/docs"]}""",
                """{"content":"Body text in 3.4s","url":"https://ex.com/docs"}""",
            )

        assertEquals("Read ex.com/docs", view.title)
        assertEquals("ex.com/docs", view.subtitle)
        assertEquals("Body text", view.detail)
    }

    @Test
    fun `web_extract joins multiple result contents`() {
        val view =
            build(
                "web_extract",
                """{"urls":["https://ex.com"]}""",
                """{"results":[{"content":"one"},{"content":"two"}]}""",
            )

        // Pinned quirk: the joiner inserts "---" but the divider-line
        // stripper in build() removes it again, leaving a blank gap.
        assertEquals("one\n\n\ntwo", view.detail)
    }

    @Test
    fun `browser_navigate done and error titles use hostname`() {
        val done = build("browser_navigate", """{"url":"https://example.com/x"}""", """{"ok":true}""")
        assertEquals("Opened example.com/x", done.title)
        assertEquals("example.com/x", done.subtitle)

        val failed =
            build(
                "browser_navigate",
                """{"url":"https://example.com/x"}""",
                """{"error":"net::ERR_CONNECTION_REFUSED","url":"https://example.com/x"}""",
            )
        assertEquals(ToolViewStatus.ERROR, failed.status)
        assertEquals("Failed to open example.com/x", failed.title)
        assertEquals("net::ERR_CONNECTION_REFUSED", failed.subtitle)
    }

    @Test
    fun `browser_snapshot summarizes controls in subtitle and drops redundant detail`() {
        val view =
            build(
                "browser_snapshot",
                "{}",
                """{"snapshot":"button \"Save\"\nbutton \"Cancel\"\nlink \"Docs\"\ntextbox \"Name\""}""",
            )

        assertEquals("2 buttons · 1 links · 1 inputs\nTop controls: Save, Cancel, Docs, Name", view.subtitle)
        // detailFor returns the same summary; redundancy filter clears it.
        assertEquals("", view.detail)
    }

    @Test
    fun `browser_click internal ref subtitle`() {
        val view = build("browser_click", """{"ref":"@e12"}""", """{"ok":true}""")

        assertEquals("Clicked page element (internal ref @e12)", view.subtitle)
    }

    @Test
    fun `browser_type shows field and value`() {
        val view = build("browser_type", """{"label":"Search","text":"kittens"}""", """{"ok":true}""")

        assertEquals("Field: Search · Value: kittens", view.subtitle)
    }

    // ── memory / facts ───────────────────────────────────────────────────

    @Test
    fun `memory add title keeps target and subtitle shows message`() {
        val view =
            build(
                "memory",
                """{"action":"add","target":"user"}""",
                """{"success":true,"message":"Saved to user memory"}""",
            )

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertEquals("Memory Saved (user)", view.title)
        assertEquals("Saved to user memory", view.subtitle)
    }

    @Test
    fun `memory update and remove verbs`() {
        assertEquals("Memory Updated", build("memory", """{"action":"update"}""", """{"success":true}""").title)
        assertEquals("Memory Removed", build("memory", """{"action":"delete"}""", """{"success":true}""").title)
        assertEquals("Saving", build("memory", """{"action":"add"}""", running = true).title)
    }

    @Test
    fun `fact_store added and listed facts`() {
        val added = build("fact_store", """{"action":"add"}""", """{"status":"added","fact_id":42}""")
        assertEquals("Fact added (ID: 42)", added.subtitle)

        val listed =
            build(
                "fact_store",
                """{"action":"search","query":"x"}""",
                """{"count":1,"results":[{"fact_id":7,"content":"user likes tea",""" +
                    """"category":"user_pref","trust_score":0.8}]}""",
            )
        assertEquals("1 facts (search: x)", listed.subtitle)
        assertEquals("#7  user likes tea\n      [user_pref]  trust: 0.80", listed.detail)
    }

    // ── cron / todo / session search / process ───────────────────────────

    @Test
    fun `cronjob list counts jobs and lists schedules`() {
        val view =
            build(
                "cronjob",
                """{"action":"list"}""",
                """{"jobs":[{"name":"daily","schedule":"0 9 * * *"},{"name":"hourly","schedule":"0 * * * *"}]}""",
            )

        assertEquals("Cron list", view.title)
        assertEquals("2 cron jobs", view.subtitle)
        assertEquals("- daily · 0 9 * * *\n- hourly · 0 * * * *", view.detail)
    }

    @Test
    fun `cronjob create shows schedule rows`() {
        val view =
            build(
                "cronjob",
                """{"action":"create","name":"reminder"}""",
                """{"name":"reminder","schedule":"0 9 * * *","next_run_at":"2026-09-11T09:00"}""",
            )

        assertEquals("Cron create", view.title)
        assertEquals("Create reminder", view.subtitle)
        assertEquals("Schedule: 0 9 * * *\nNext run: 2026-09-11T09:00", view.detail)
    }

    @Test
    fun `todo renders summary counts and status markers`() {
        val view =
            build(
                "todo",
                """{"action":"update"}""",
                """{"summary":{"total":3,"pending":1,"in_progress":1,"completed":1},"todos":[
                    {"id":"1","content":"one","status":"pending"},
                    {"id":"2","content":"two","status":"in_progress","parent":"1"},
                    {"id":"3","content":"three","status":"completed"}
                ]}""",
            )

        assertEquals("3 items (1 pending, 1 in_progress, 1 completed)", view.subtitle)
        assertEquals("[ ] 1. one\n  ↳ [>] 2. two\n[x] 3. three", view.detail)
    }

    @Test
    fun `session_search modes produce mode-specific subtitles`() {
        val discover =
            build(
                "session_search",
                """{"query":"auth"}""",
                """{"mode":"discover","count":2,"query":"auth","results":[{"title":"Auth refactor"}]}""",
            )
        assertEquals("2 sessions: auth", discover.subtitle)

        val read =
            build(
                "session_search",
                "{}",
                """{"mode":"read","message_count":5,"truncated":true,"messages":[]}""",
            )
        assertEquals("5 messages (truncated)", read.subtitle)

        val browse = build("session_search", "{}", """{"mode":"browse","count":3}""")
        assertEquals("3 recent sessions", browse.subtitle)
    }

    @Test
    fun `process list renders sessions with status`() {
        val view =
            build(
                "process",
                """{"action":"list"}""",
                """{"processes":[{"session_id":"p1","status":"running","command":"npm start","running":true}]}""",
            )

        assertEquals("list", view.subtitle)
        assertEquals("📌 p1: npm start\n     Status: running\n     Running: true", view.detail)
    }

    // ── delegation / messaging fall back to the generic engine ───────────

    @Test
    fun `delegate_task uses generic title and unwrapped response`() {
        val view =
            build(
                "delegate_task",
                """{"goal":"Fix tests"}""",
                """{"response":"All done","duration_s":63}""",
            )

        assertEquals("Delegate Task", view.title)
        assertEquals("All done", view.subtitle)
        assertEquals("", view.detail)
        assertEquals("1m 3s", view.durationLabel)
    }

    @Test
    fun `send_message summarizes fields generically`() {
        val view = build("send_message", "{}", """{"status":"sent","channel":"#general"}""")

        assertEquals("Send Message", view.title)
        assertEquals("- Status: sent", view.subtitle)
        assertEquals("- Status: sent\n- Channel: #general", view.detail)
    }

    // ── media tools ──────────────────────────────────────────────────────

    @Test
    fun `image_generate subtitle carries url and detail carries prompt`() {
        val view =
            build(
                "image_generate",
                """{"prompt":"a cat"}""",
                """{"image":"https://img.example/cat.png","modality":"image"}""",
            )

        assertEquals("https://img.example/cat.png (image)", view.subtitle)
        assertEquals("🖼️ a cat\n\n🔗 https://img.example/cat.png", view.detail)
        // Pinned: imageUrlFor does not read the "image" key, so no thumbnail.
        assertNull(view.imageUrl)
    }

    @Test
    fun `vision_analyze exposes image url and description`() {
        val view =
            build(
                "vision_analyze",
                """{"image_url":"data:image/png;base64,AAA"}""",
                """{"description":"A cat on a mat"}""",
            )

        assertEquals("data:image/png;base64,AAA", view.imageUrl)
        assertEquals("A cat on a mat", view.detail)
        assertEquals("data:image/png;base64,AAA", view.subtitle)
    }

    @Test
    fun `image url only accepted for data or remote image extensions`() {
        assertEquals(
            "https://a.b/i.jpg?x=1",
            build("weird_tool", "{}", """{"url":"https://a.b/i.jpg?x=1"}""").imageUrl,
        )
        assertNull(build("weird_tool", "{}", """{"url":"https://a.b/page"}""").imageUrl)
    }

    @Test
    fun `x_search renders answer with citations and degraded marker`() {
        val view =
            build(
                "x_search",
                """{"query":"hermes"}""",
                """{"answer":"It is an agent.","degraded":true,"citations":[]}""",
            )

        assertEquals("hermes (no citations)", view.subtitle)
        assertEquals("It is an agent.\n\n⚠️ No citations — answer based on model's knowledge", view.detail)
    }

    // ── skills / tool discovery / projects / computer use ────────────────

    @Test
    fun `skills_list counts skills and renders entries`() {
        val view =
            build(
                "skills_list",
                """{"category":"research"}""",
                """{"skills":[{"name":"summarize","description":"Sums up"},{"name":"extract"}]}""",
            )

        assertEquals("2 skills (research)", view.subtitle)
        assertEquals("📌 summarize\n     Sums up\n\n📌 extract", view.detail)
    }

    @Test
    fun `skill_view truncates long content at 500 chars`() {
        val longContent = "x".repeat(620)
        val view = build("skill_view", """{"name":"summarize"}""", """{"content":"$longContent"}""")

        assertEquals("summarize", view.subtitle)
        assertEquals("${"x".repeat(500)}\n... [120 more chars]", view.detail)
    }

    @Test
    fun `tool_search shows match count and entries`() {
        val view =
            build(
                "tool_search",
                """{"query":"files"}""",
                """{"matches":[{"name":"read_file","description":"Reads"},{"name":"write_file"}]}""",
            )

        assertEquals("files (2 matches)", view.subtitle)
        assertEquals("🔧 read_file\n     Reads\n\n🔧 write_file", view.detail)
    }

    @Test
    fun `read_terminal shows line window and terminal block`() {
        val view =
            build(
                "read_terminal",
                """{"session_id":"s1"}""",
                """{"total_lines":50,"start":1,"end":20,"text":"line one","cursor_row":5}""",
            )

        assertEquals("Lines 1-20 of 50, cursor at row 5", view.subtitle)
        assertEquals("━━━ Terminal ━━━     (1:20 / 50) │ cursor row 5\nline one", view.detail)
    }

    @Test
    fun `computer_use apps list and action ack`() {
        val apps = build("computer_use", """{"action":"list_apps"}""", """{"apps":[{"name":"Firefox","pid":42}]}""")
        assertEquals("1 apps", apps.subtitle)
        assertEquals("1. Firefox (PID: 42)", apps.detail.trim())

        val action = build("computer_use", """{"action":"click"}""", """{"ok":true}""")
        assertEquals("click ✓", action.subtitle)
        assertEquals("click: ✅ ok", action.detail)
    }

    // ── payload normalization: wrappers, strings, malformed JSON ─────────

    @Test
    fun `wrapped payloads unwrap for unknown tools`() {
        val view = build("weird_tool", "{}", """{"data":{"message":"All good"}}""")

        assertEquals("Weird Tool", view.title)
        assertEquals("All good", view.subtitle)
        // Redundant with the subtitle, so cleared.
        assertEquals("", view.detail)
    }

    @Test
    fun `stringified json result is re-parsed`() {
        val view =
            ToolViewBuilder.build(
                "terminal",
                json("""{"command":"ls"}"""),
                JsonPrimitive("""{"output":"hi","exit_code":0}"""),
            )

        assertEquals("hi", view.stdout)
        assertEquals(0, view.exitCode)
    }

    @Test
    fun `malformed json string result degrades to success with no streams`() {
        val view = ToolViewBuilder.build("terminal", json("""{"command":"ls"}"""), JsonPrimitive("{not json"))

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertEquals("Ran ls", view.title)
        assertNull(view.stdout)
        assertNull(view.exitCode)
    }

    @Test
    fun `array result summarizes items generically`() {
        val view = ToolViewBuilder.build("weird_tool", null, json("""[{"name":"alpha"},{"name":"beta"}]"""))

        assertEquals(ToolViewStatus.SUCCESS, view.status)
        assertEquals("- alpha\n- beta", view.detail)
    }

    // ── counts and duration labels ───────────────────────────────────────

    @Test
    fun `count fields drive count labels with nouns`() {
        assertEquals("5 items", build("weird_tool", "{}", """{"count":"5"}""").countLabel)
        assertEquals("2 matches", build("weird_tool", "{}", """{"match_count":2}""").countLabel)
        assertEquals("3 files", build("weird_tool", "{}", """{"files":["a","b","c"]}""").countLabel)
        assertEquals("4 sessions", build("weird_tool", "{}", """{"session_count":4}""").countLabel)
    }

    @Test
    fun `count from summary text`() {
        assertEquals("4 matches", build("weird_tool", "{}", """{"summary":"Found 4 matches in repo"}""").countLabel)
    }

    @Test
    fun `duration label formats across magnitudes`() {
        assertEquals("500ms", build("weird_tool", "{}", """{"duration_s":0.5}""").durationLabel)
        assertEquals("9.5s", build("weird_tool", "{}", """{"duration_s":9.5}""").durationLabel)
        assertEquals("12s", build("weird_tool", "{}", """{"duration_s":12.3}""").durationLabel)
        assertEquals("1m 3s", build("weird_tool", "{}", """{"duration_s":63}""").durationLabel)
        assertEquals("1h 1m", build("weird_tool", "{}", """{"duration_s":3660}""").durationLabel)
    }

    // ── unknown tool fallback ────────────────────────────────────────────

    @Test
    fun `unknown tool gets humanized title and heuristic summary`() {
        val view = build("weird_tool", "{}", """{"result":{"data":{"title":"Build report","completed":true}}}""")

        assertEquals("Weird Tool", view.title)
        assertEquals("- Title: Build report", view.subtitle)
        assertEquals("- Title: Build report\n- Completed: true", view.detail)
    }

    @Test
    fun `generic titles strip browser and web prefixes`() {
        assertEquals("Fact Store", ToolViewBuilder.genericTitleFor("fact_store"))
        assertEquals("Cdp", ToolViewBuilder.genericTitleFor("browser_cdp"))
        assertEquals("Tool", ToolViewBuilder.genericTitleFor(null))
    }
}
