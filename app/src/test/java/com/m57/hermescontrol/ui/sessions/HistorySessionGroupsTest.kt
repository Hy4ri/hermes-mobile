package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistorySessionGroupsTest {
    @Test
    fun groupsDurableJobIdsNotTitles() {
        val groups =
            automationGroups(
                listOf(
                    SessionInfo("cron_job_one_20260905_100000", title = "Same", source = "cron"),
                    SessionInfo("cron_job_two_20260905_100000", title = "Same", source = "cron"),
                    SessionInfo("cron_job_one_20260905_110000", title = "Renamed", source = "cron"),
                ),
            )
        assertEquals(listOf("job_one", "job_two"), groups.map { it.jobId })
        assertEquals(2, groups.first().sessions.size)
        assertEquals(
            "cron_job_one_20260905_110000",
            groups
                .first()
                .sessions
                .last()
                .id,
        )
    }

    @Test
    fun unknownRunIdsRemainIndependentlyAccessible() {
        val groups =
            automationGroups(
                listOf(
                    SessionInfo("legacy-a", title = "Same", source = "cron"),
                    SessionInfo("legacy-b", title = "Same", source = "cron"),
                ),
            )
        assertEquals(2, groups.size)
        assertNull(groups.first().jobId)
        assertEquals(listOf("legacy-a", "legacy-b"), groups.flatMap { it.sessions }.map { it.id })
    }

    @Test
    fun repeatedPagePinsDoNotDuplicateRuns() {
        val run = SessionInfo("cron_job_20260905_100000", source = "cron", pinned = true)
        assertEquals(1, automationGroups(listOf(run, run)).single().sessions.size)
    }

    @Test
    fun cronLikeConversationIdDoesNotInventAJob() {
        val groups = automationGroups(listOf(SessionInfo("cron_job_20260905_100000", source = "cli")))
        assertNull(groups.single().jobId)
    }

    @Test
    fun derivesJobTitleFromLatestSessionWhileGroupingByJobId() {
        val groups =
            automationGroups(
                listOf(
                    SessionInfo(
                        "cron_nightly_20260905_100000",
                        title = "Nightly Backup · Sep 05 10:00",
                        source = "cron",
                    ),
                    SessionInfo(
                        "cron_nightly_20260905_090000",
                        title = "Nightly Backup · Sep 05 09:00",
                        source = "cron",
                    ),
                ),
            )
        assertEquals(1, groups.size)
        assertEquals("nightly", groups.single().jobId)
        assertEquals("Nightly Backup", groups.single().title)
    }
}
