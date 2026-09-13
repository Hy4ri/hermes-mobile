package com.m57.hermescontrol.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsPagingTest {
    @Test
    fun `resolveInitialPaging hasMore when received full page and total exceeds accumulated`() {
        val resolution =
            SessionsPaging.resolveInitialPaging(
                receivedCount = 50,
                pageSize = 50,
                backendTotal = 120,
                accumulatedCount = 50,
            )
        assertTrue(resolution.hasMore)
        assertEquals(120, resolution.total)
    }

    @Test
    fun `resolveInitialPaging no more when received less than page size`() {
        val resolution =
            SessionsPaging.resolveInitialPaging(
                receivedCount = 30,
                pageSize = 50,
                backendTotal = 30,
                accumulatedCount = 30,
            )
        assertFalse(resolution.hasMore)
        assertEquals(30, resolution.total)
    }

    @Test
    fun `resolveInitialPaging no more when backendTotal equals accumulated`() {
        val resolution =
            SessionsPaging.resolveInitialPaging(
                receivedCount = 50,
                pageSize = 50,
                backendTotal = 50,
                accumulatedCount = 50,
            )
        assertFalse(resolution.hasMore)
        assertEquals(50, resolution.total)
    }

    @Test
    fun `resolveLoadMorePaging hasMore when full page added new items and total exceeds accumulated`() {
        val resolution =
            SessionsPaging.resolveLoadMorePaging(
                receivedCount = 50,
                pageSize = 50,
                addedNewItems = true,
                backendTotal = 150,
                accumulatedCount = 100,
            )
        assertTrue(resolution.hasMore)
        assertEquals(150, resolution.total)
    }

    @Test
    fun `resolveLoadMorePaging no more when no new items added`() {
        val resolution =
            SessionsPaging.resolveLoadMorePaging(
                receivedCount = 50,
                pageSize = 50,
                addedNewItems = false,
                backendTotal = 150,
                accumulatedCount = 100,
            )
        assertFalse(resolution.hasMore)
        assertEquals(100, resolution.total)
    }
}
