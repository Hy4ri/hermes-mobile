package com.m57.hermescontrol.data.model

import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanSerializationTest {
    private val json = OkHttpProvider.json

    @Test
    fun testBoardResponseSerialization() {
        val raw =
            """
            {
                "columns": [
                    {
                        "name": "todo",
                        "tasks": [
                            {
                                "id": "t_101",
                                "title": "Build mobile Kanban",
                                "body": "Full desktop parity",
                                "status": "todo",
                                "assignee": "researcher",
                                "priority": 1,
                                "tenant": "mobile",
                                "created_at": 1718000000,
                                "latest_summary": "Initial draft ready",
                                "comment_count": 3,
                                "link_counts": { "parents": 1, "children": 2 },
                                "progress": { "done": 1, "total": 2 },
                                "warnings": { "count": 1, "highest_severity": "warning" },
                                "started_at": 1718000100,
                                "worker_pid": 12345,
                                "last_heartbeat_at": 1718000200
                            }
                        ]
                    }
                ],
                "assignees": ["researcher", "reviewer"],
                "tenants": ["mobile"],
                "latest_event_id": 42,
                "now": 1718000500
            }
            """.trimIndent()

        val decoded = json.decodeFromString<KanbanBoardResponse>(raw)
        assertEquals(1, decoded.columns.size)
        assertEquals("todo", decoded.columns[0].name)
        val task = decoded.columns[0].tasks[0]
        assertEquals("t_101", task.id)
        assertEquals("Build mobile Kanban", task.title)
        assertEquals("Full desktop parity", task.body)
        assertEquals("Full desktop parity", task.description)
        assertEquals("researcher", task.assignee)
        assertEquals("researcher", task.assignedTo)
        assertEquals(1, task.priority)
        assertEquals(3, task.commentCount)
        assertEquals(1, task.linkCounts?.parents)
        assertEquals(2, task.linkCounts?.children)
        assertEquals(1, task.progress?.done)
        assertEquals(2, task.progress?.total)
        assertEquals(1, task.warnings?.count)
        assertEquals("warning", task.warnings?.highestSeverity)
        assertEquals(12345, task.workerPid)
        assertEquals(42L, decoded.latestEventId)
        assertEquals(1718000500L, decoded.now)
    }

    @Test
    fun testTaskDetailResponseSerialization() {
        val raw =
            """
            {
                "task": {
                    "id": "t_202",
                    "title": "Fix memory leak",
                    "body": "Detailed description",
                    "status": "running",
                    "assignee": "debugger",
                    "result": "Resolved leak",
                    "created_by": "m57",
                    "model_override": "gpt-4o",
                    "provider_override": "openai",
                    "reasoning_effort": "high",
                    "completed_at": 1718000900,
                    "last_failure_error": null,
                    "workspace_kind": "worktree",
                    "workspace_path": "/tmp/wt-1",
                    "branch_name": "feat/leak-fix",
                    "consecutive_failures": 0,
                    "diagnostics": [
                        {
                            "kind": "stale_heartbeat",
                            "severity": "warning",
                            "title": "Worker heartbeat missing",
                            "detail": "Last ping was 3 minutes ago",
                            "count": 1,
                            "last_seen_at": 1718000850,
                            "actions": [
                                { "kind": "reclaim", "label": "Reclaim task", "suggested": true }
                            ]
                        }
                    ]
                },
                "comments": [
                    {
                        "id": 1,
                        "author": "mobile",
                        "body": "Steering comment",
                        "created_at": 1718000800
                    }
                ],
                "events": [
                    {
                        "id": 10,
                        "kind": "status",
                        "payload": { "status": "running" },
                        "created_at": 1718000700
                    }
                ],
                "attachments": [
                    {
                        "id": 5,
                        "task_id": "t_202",
                        "filename": "leak.png",
                        "content_type": "image/png",
                        "size": 1024,
                        "uploaded_by": "mobile",
                        "stored_path": "/files/leak.png",
                        "created_at": 1718000750
                    }
                ],
                "links": {
                    "parents": ["t_100"],
                    "children": ["t_300"]
                },
                "child_results": [
                    {
                        "id": "t_300",
                        "title": "Child task",
                        "status": "done",
                        "latest_summary": "Finished child",
                        "result": "OK"
                    }
                ],
                "runs": [
                    {
                        "id": 7,
                        "profile": "debugger",
                        "status": "done",
                        "outcome": "completed",
                        "summary": "Completed attempt 1",
                        "worker_pid": 9876,
                        "started_at": 1718000500,
                        "ended_at": 1718000900
                    }
                ]
            }
            """.trimIndent()

        val decoded = json.decodeFromString<KanbanTaskDetailResponse>(raw)
        assertEquals("t_202", decoded.task.id)
        assertEquals("gpt-4o", decoded.task.modelOverride)
        assertEquals("high", decoded.task.reasoningEffort)
        assertEquals(1, decoded.task.diagnostics.size)
        assertEquals("stale_heartbeat", decoded.task.diagnostics[0].kind)
        assertTrue(
            decoded.task.diagnostics[0]
                .actions[0]
                .suggested,
        )
        assertEquals(1, decoded.comments.size)
        assertEquals("Steering comment", decoded.comments[0].body)
        assertEquals(1, decoded.events.size)
        assertEquals("status", decoded.events[0].kind)
        assertEquals(1, decoded.attachments?.size)
        assertEquals("leak.png", decoded.attachments?.get(0)?.filename)
        assertEquals(listOf("t_100"), decoded.links.parents)
        assertEquals(listOf("t_300"), decoded.links.children)
        assertEquals(1, decoded.childResults.size)
        assertEquals(1, decoded.runs.size)
        assertEquals(7L, decoded.runs[0].id)
        assertEquals("completed", decoded.runs[0].outcome)
    }

    @Test
    fun testCreateAndUpdatePayloadSerialization() {
        val createBody =
            CreateTaskBody(
                title = "New Task",
                body = "Task description",
                assignee = "default",
                priority = 2,
                parents = listOf("t_001"),
                triage = true,
                modelOverride = "claude-3-5-sonnet",
                reasoningEffort = "none",
            )
        val createJson = json.encodeToString(createBody)
        assertTrue(createJson.contains("\"title\":\"New Task\""))
        assertTrue(createJson.contains("\"triage\":true"))
        assertTrue(createJson.contains("\"parents\":[\"t_001\"]"))
        assertTrue(createJson.contains("\"reasoning_effort\":\"none\""))

        val updateBody =
            UpdateTaskBody(
                status = "blocked",
                blockReason = "Waiting for credentials",
                clearModelOverride = true,
                clearReasoningEffort = true,
            )
        val updateJson = json.encodeToString(updateBody)
        assertTrue(updateJson.contains("\"status\":\"blocked\""))
        assertTrue(updateJson.contains("\"block_reason\":\"Waiting for credentials\""))
        assertTrue(updateJson.contains("\"clear_model_override\":true"))
        assertTrue(updateJson.contains("\"clear_reasoning_effort\":true"))
    }

    @Test
    fun testBulkAndSettingsSerialization() {
        val bulk =
            BulkTasksBody(
                ids = listOf("t_1", "t_2"),
                status = "ready",
                reclaimFirst = true,
            )
        val bulkJson = json.encodeToString(bulk)
        assertTrue(bulkJson.contains("\"ids\":[\"t_1\",\"t_2\"]"))
        assertTrue(bulkJson.contains("\"reclaim_first\":true"))

        val orchestrationRaw =
            """
            {
                "orchestrator_profile": "lead",
                "default_assignee": "coder",
                "auto_decompose": true,
                "auto_promote_children": false,
                "resolved_orchestrator_profile": "lead",
                "resolved_default_assignee": "coder",
                "active_profile": "default"
            }
            """.trimIndent()
        val orchestration = json.decodeFromString<OrchestrationSettings>(orchestrationRaw)
        assertEquals("lead", orchestration.orchestratorProfile)
        assertEquals("coder", orchestration.defaultAssignee)
        assertTrue(orchestration.autoDecompose)
        assertFalse(orchestration.autoPromoteChildren)
    }

    @Test
    fun testWorkerLogAndEstimateSerialization() {
        val logRaw =
            """
            {
                "task_id": "t_999",
                "path": "/logs/worker.log",
                "exists": true,
                "size_bytes": 4096,
                "content": "Running tests...\nAll passed!",
                "truncated": false
            }
            """.trimIndent()
        val log = json.decodeFromString<WorkerLog>(logRaw)
        assertEquals("t_999", log.taskId)
        assertTrue(log.exists)
        assertEquals(4096L, log.sizeBytes)
        assertEquals("Running tests...\nAll passed!", log.content)
        assertFalse(log.truncated)

        val estRaw =
            """
            {
                "ok": true,
                "est_tokens": 15000,
                "complexity": "M",
                "rationale": "Multi-file changes needed",
                "model": "auxiliary"
            }
            """.trimIndent()
        val estimate = json.decodeFromString<TaskEstimate>(estRaw)
        assertTrue(estimate.ok)
        assertEquals(15000, estimate.estTokens)
        assertEquals("M", estimate.complexity)
        assertEquals("Multi-file changes needed", estimate.rationale)
    }

    @Test
    fun testBulkAssignmentUsesExplicitEmptyStringAndReclaim() {
        val assignJson =
            json.encodeToString(
                BulkTasksBody(
                    ids = listOf("t_1"),
                    assignee = "researcher",
                    reclaimFirst = true,
                ),
            )
        val unassignJson =
            json.encodeToString(
                BulkTasksBody(
                    ids = listOf("t_1"),
                    assignee = "",
                    reclaimFirst = true,
                ),
            )

        assertTrue(assignJson.contains("\"assignee\":\"researcher\""))
        assertTrue(assignJson.contains("\"reclaim_first\":true"))
        assertTrue(unassignJson.contains("\"assignee\":\"\""))
        assertTrue(unassignJson.contains("\"reclaim_first\":true"))
    }
}
