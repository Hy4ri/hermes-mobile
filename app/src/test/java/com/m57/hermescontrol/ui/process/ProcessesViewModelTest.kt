package com.m57.hermescontrol.ui.process

import com.m57.hermescontrol.data.model.ProcessInfo
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.contract.ProcessKillParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.SessionIdParams
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        val raw: JsonElement =
            buildJsonObject {
                put(
                    "processes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("session_id", "p1")
                                put("command", "python train.py --epochs 10")
                                put("pid", 1234)
                                put("status", "running")
                                put("uptime_seconds", 95)
                                put("cwd", "/work")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("session_id", "p2")
                                put("command", "echo done")
                                put("status", "exited")
                                put("exit_code", 0)
                            },
                        )
                    },
                )
            }

        val listParams = slot<SessionIdParams>()
        coEvery { HermesWsClient.call(RpcMethods.PROCESS_LIST, capture(listParams), any(), any()) } returns raw

        val vm = ProcessesViewModel()
        vm.load()

        assertEquals(SessionIdParams("sess-1"), listParams.captured)
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
        val killParamsSlot = slot<ProcessKillParams>()

        coEvery { HermesWsClient.call(RpcMethods.PROCESS_LIST, any(), any(), any()) } answers {
            listCalls++
            buildJsonObject { put("processes", JsonArray(emptyList())) }
        }
        coEvery { HermesWsClient.call(RpcMethods.PROCESS_KILL, capture(killParamsSlot), any(), any()) } answers {
            buildJsonObject { put("killed", true) }
        }

        val vm = ProcessesViewModel()
        vm.kill("p1")

        assertEquals(ProcessKillParams(sessionId = "sess-1", processId = "p1"), killParamsSlot.captured)
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
