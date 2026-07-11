package com.m57.hermescontrol.ui.process

import com.m57.hermescontrol.data.model.ProcessInfo
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessesViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(HermesWsClient)
        ActiveSessionHolder.set("sess-1")
    }

    @After
    fun tearDown() {
        unmockkAll()
        ActiveSessionHolder.set(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `process list parses running and exited entries`() {
        val raw =
            mapOf(
                "processes" to
                    listOf(
                        mapOf(
                            "session_id" to "p1",
                            "command" to "python train.py --epochs 10",
                            "pid" to 1234,
                            "status" to "running",
                            "uptime_seconds" to 95,
                            "cwd" to "/work",
                        ),
                        mapOf(
                            "session_id" to "p2",
                            "command" to "echo done",
                            "status" to "exited",
                            "exit_code" to 0,
                        ),
                    ),
            )

        every {
            HermesWsClient.request(WsMethods.PROCESS_LIST, any())
        } returns CompletableDeferred(raw)

        val vm = ProcessesViewModel()
        vm.load()

        val procs = vm.uiState.value.processes
        assertEquals(2, procs.size)

        val running = procs[0]
        assertEquals("p1", running.sessionId)
        assertTrue(running.isRunning)
        assertEquals(1234, running.pid)
        assertEquals("python train.py --epochs 10", running.command)
        assertEquals("python train.py --epochs 10", running.title)

        val exited = procs[1]
        assertEquals("p2", exited.sessionId)
        assertFalse(exited.isRunning)
        assertEquals(0, exited.exitCode)
        assertEquals("echo done", exited.title)
    }

    @Test
    fun `kill issues process kill with session-scoped params then refreshes`() {
        var listCalls = 0
        val killParamsSlot = slot<Map<String, Any>>()

        every {
            HermesWsClient.request(WsMethods.PROCESS_LIST, any())
        } answers {
            listCalls++
            CompletableDeferred(mapOf("processes" to emptyList<Any>()))
        }
        every {
            HermesWsClient.request(WsMethods.PROCESS_KILL, capture(killParamsSlot))
        } answers {
            CompletableDeferred(mapOf("killed" to true))
        }

        val vm = ProcessesViewModel()
        vm.kill("p1")

        val killParams = killParamsSlot.captured
        assertEquals("p1", killParams["process_id"])
        assertEquals("sess-1", killParams["session_id"])
        // list is called once on init (session emitted) + once after kill refresh
        assertTrue(listCalls >= 2)
        assertNull(vm.uiState.value.killingId)
    }

    @Test
    fun `fromMap returns null when session_id missing`() {
        val parsed = ProcessInfo.fromMap(mapOf("command" to "ls"))
        assertNull(parsed)
    }
}
