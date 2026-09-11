package com.m57.hermescontrol.ui.sessions

data class SessionPaginationResolution(
    val hasMore: Boolean,
    val total: Int,
)

object SessionsPaging {
    /**
     * Computes hasMore and effective total when refreshing or loading the initial page of sessions.
     */
    fun resolveInitialPaging(
        receivedCount: Int,
        pageSize: Int,
        backendTotal: Int,
        accumulatedCount: Int,
    ): SessionPaginationResolution {
        val hasMore = receivedCount >= pageSize && backendTotal > accumulatedCount
        return SessionPaginationResolution(
            hasMore = hasMore,
            total = if (hasMore) backendTotal else accumulatedCount,
        )
    }

    /**
     * Computes hasMore and effective total when appending a page of sessions.
     */
    fun resolveLoadMorePaging(
        receivedCount: Int,
        pageSize: Int,
        addedNewItems: Boolean,
        backendTotal: Int,
        accumulatedCount: Int,
    ): SessionPaginationResolution {
        val receivedFullPage = receivedCount >= pageSize
        val hasMore = receivedFullPage && addedNewItems && backendTotal > accumulatedCount
        return SessionPaginationResolution(
            hasMore = hasMore,
            total = if (hasMore) backendTotal else accumulatedCount,
        )
    }
}
