package com.m57.hermescontrol.ui.chat.tool.render

import com.m57.hermescontrol.ui.chat.tool.ToolCall
import com.m57.hermescontrol.ui.chat.tool.ToolJson
import com.m57.hermescontrol.ui.chat.tool.ToolRenderer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * `delegate_task`: fan-out of subagent tasks.
 * Covers joined results, background dispatch handles, and list/steer/stop acks.
 */
internal object DelegateTaskRenderer : ToolRenderer {
    private fun goals(call: ToolCall): List<String> {
        val tasks = call.args?.get("tasks") as? JsonArray
        val fromTasks =
            tasks?.mapIndexedNotNull { i, el ->
                ToolJson.parseMaybeObject(el)
                    ?.let { ToolJson.firstString(it, listOf("goal")) }
                    ?.ifEmpty { "Task ${i + 1}" }
            } ?: emptyList()
        if (fromTasks.isNotEmpty()) return fromTasks
        return ToolJson.firstString(call.args, listOf("goal"))
            .takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    }

    private fun action(call: ToolCall) = ToolJson.firstString(call.args, listOf("action")).ifEmpty { "spawn" }

    override fun pendingTitle(call: ToolCall): String =
        when (action(call)) {
            "list" -> "Listing subagents"
            "steer" -> "Steering subagent"
            "stop" -> "Stopping subagent"
            else -> {
                val n = goals(call).size.coerceAtLeast(1)
                "Delegating $n task${if (n == 1) "" else "s"}"
            }
        }

    override fun doneTitle(call: ToolCall): String {
        val result = call.result
        return when {
            action(call) == "list" -> {
                val n = ToolJson.intValue(result?.get("count")) ?: 0
                "$n subagent${if (n == 1) "" else "s"}"
            }
            action(call) == "steer" -> "Steer queued"
            action(call) == "stop" -> "Stop requested"
            ToolJson.firstString(result, listOf("status")) == "dispatched" -> {
                val n = ToolJson.intValue(result?.get("count")) ?: goals(call).size
                "Dispatched $n background task${if (n == 1) "" else "s"}"
            }
            else -> {
                val results = result?.get("results") as? JsonArray
                val n = results?.size ?: goals(call).size.coerceAtLeast(1)
                val failed =
                    results?.count {
                        ToolJson.firstString(ToolJson.parseMaybeObject(it), listOf("status")) == "failed"
                    } ?: 0
                if (failed > 0) {
                    "Delegated $n tasks · $failed failed"
                } else {
                    "Delegated $n task${if (n == 1) "" else "s"}"
                }
            }
        }
    }

    override fun subtitle(call: ToolCall): String = ToolJson.compactPreview(goals(call).firstOrNull() ?: "", 120)

    override fun detail(call: ToolCall): String {
        val result = call.result
        // list action: one row per live subagent
        (result?.get("subagents") as? JsonArray)?.let { subs ->
            return subs.mapNotNull { el ->
                val s = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val goal = ToolJson.compactPreview(ToolJson.firstString(s, listOf("goal")), 80)
                val status = ToolJson.firstString(s, listOf("status"))
                val secs = ToolJson.numberValue(s["running_seconds"])
                val time = secs?.let { " · ${ToolJson.formatDurationSeconds(it)}" } ?: ""
                "- [$status] $goal$time"
            }.joinToString("\n")
        }
        // joined spawn: per-task result rows
        val rows =
            (result?.get("results") as? JsonArray)?.mapNotNull { el ->
                val r = ToolJson.parseMaybeObject(el) ?: return@mapNotNull null
                val idx = ToolJson.intValue(r["task_index"]) ?: 0
                val ok = ToolJson.firstString(r, listOf("status")) != "failed"
                val marker = if (ok) "[x]" else "[!]"
                val goal = goals(call).getOrNull(idx) ?: "Task ${idx + 1}"
                val model = ToolJson.firstString(r, listOf("model"))
                val dur =
                    ToolJson.numberValue(r["duration_seconds"])
                        ?.let { ToolJson.formatDurationSeconds(it) } ?: ""
                val meta = listOf(model, dur).filter { it.isNotEmpty() }.joinToString(" · ")
                val summary =
                    ToolJson.compactPreview(
                        ToolJson.firstString(r, listOf("summary", "error")),
                        160,
                    )
                buildString {
                    append("$marker ${ToolJson.compactPreview(goal, 80)}")
                    if (meta.isNotEmpty()) append("  ($meta)")
                    if (summary.isNotEmpty()) append("\n    $summary")
                }
            } ?: emptyList()
        if (rows.isNotEmpty()) return rows.joinToString("\n")
        // dispatched handle / steer / stop acks
        val note = ToolJson.firstString(result, listOf("note"))
        val goalsArr =
            (result?.get("goals") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.joinToString("\n") { "- ${ToolJson.compactPreview(it, 100)}" } ?: ""
        return listOf(goalsArr, note).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
