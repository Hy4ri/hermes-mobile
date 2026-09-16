package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.remote.KanbanApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface KanbanDispatcherNudger {
    fun scheduleNudge(board: String?)
}

class DefaultKanbanDispatcherNudger(
    private val apiProvider: () -> KanbanApiService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val debounceMs: Long = 400L,
) : KanbanDispatcherNudger {
    private val lock = Any()
    private val boardJobs = mutableMapOf<String?, Job>()

    override fun scheduleNudge(board: String?) {
        synchronized(lock) {
            boardJobs[board]?.cancel()
            boardJobs[board] =
                scope.launch {
                    delay(debounceMs)
                    runCatching {
                        apiProvider().nudgeDispatcher(board)
                    }
                }
        }
    }
}
