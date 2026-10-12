package com.m57.hermescontrol.ui.chat

import android.util.Log
import com.m57.hermescontrol.data.model.ProcessInfo
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatProcessesDelegateTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val uiState = MutableStateFlow(ChatUiState(currentSessionId = "s1"))
    private var sessionId: String? = "s1"
    private var listed: List<ProcessInfo> = emptyList()
    private var listError: Exception? = null
    private val killed = mutableListOf<Pair<String, String>>()

    private fun delegate() =
        ChatProcessesDelegate(
            uiState = uiState,
            scope = scope,
            ioDispatcher = dispatcher,
            runtimeSessionId = { sessionId },
            listProcesses = { listError?.let { throw it } ?: listed },
            killProcess = { sid, pid -> killed += sid to pid },
        )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun proc(
        id: String,
        status: String = "running",
    ) = ProcessInfo(sessionId = id, command = "cmd $id", status = status)

    @Test
    fun `refresh keeps only running processes`() =
        scope.runTest {
            listed = listOf(proc("a"), proc("b", "exited"))
            delegate().refresh()
            advanceUntilIdle()
            assertEquals(listOf("a"), uiState.value.backgroundProcesses.map { it.sessionId })
        }

    @Test
    fun `refresh failure keeps the previous list`() =
        scope.runTest {
            val d = delegate()
            listed = listOf(proc("a"))
            d.refresh()
            advanceUntilIdle()
            listError = IllegalStateException("boom")
            d.refresh()
            advanceUntilIdle()
            assertEquals(listOf("a"), uiState.value.backgroundProcesses.map { it.sessionId })
        }

    @Test
    fun `refresh without a session does nothing`() =
        scope.runTest {
            sessionId = null
            listed = listOf(proc("a"))
            delegate().refresh()
            advanceUntilIdle()
            assertEquals(emptyList<ProcessInfo>(), uiState.value.backgroundProcesses)
        }

    @Test
    fun `result for a switched session is dropped`() =
        scope.runTest {
            val d = delegate()
            listed = listOf(proc("a"))
            d.refresh("s1")
            sessionId = "s2"
            advanceUntilIdle()
            assertEquals(emptyList<ProcessInfo>(), uiState.value.backgroundProcesses)
        }

    @Test
    fun `kill sends session scoped ids then re-syncs and clears the spinner`() =
        scope.runTest {
            listed = listOf(proc("a"))
            val d = delegate()
            d.refresh()
            advanceUntilIdle()
            listed = emptyList()
            d.kill("a")
            advanceUntilIdle()
            assertEquals(listOf("s1" to "a"), killed)
            assertNull(uiState.value.killingProcessId)
            assertEquals(emptyList<ProcessInfo>(), uiState.value.backgroundProcesses)
        }

    @Test
    fun `parseProcessList skips entries without session_id`() {
        val parsed =
            ChatProcessesDelegate.parseProcessList(
                mapOf(
                    "processes" to
                        listOf(
                            mapOf("session_id" to "p1", "command" to "ls", "status" to "running", "pid" to 7.0),
                            mapOf("command" to "orphan"),
                        ),
                ),
            )
        assertEquals(1, parsed.size)
        assertEquals(7, parsed[0].pid)
    }
}
