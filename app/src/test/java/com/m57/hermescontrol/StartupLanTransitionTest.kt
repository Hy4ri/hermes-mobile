package com.m57.hermescontrol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupLanTransitionTest {
    @Test
    fun startupDialWaitsForAdmissionAndResumesOnce() =
        runTest {
            val states = MutableStateFlow(StartupLanState.Checking)
            var dialCount = 0
            val dial =
                launch {
                    awaitStartupLanAdmission(states)
                    dialCount++
                }
            for (state in listOf(
                StartupLanState.Checking,
                StartupLanState.Request,
                StartupLanState.Denied,
                StartupLanState.ChooseConnection,
                StartupLanState.Request,
            )) {
                states.value = state
                runCurrent()
                assertEquals(0, dialCount)
            }
            states.value = StartupLanState.Ready
            runCurrent()
            assertEquals(1, dialCount)
            states.value = StartupLanState.Ready
            runCurrent()
            assertEquals(1, dialCount)
            assertTrue(dial.isCompleted)
        }

    @Test
    fun stalePermissionResultsDoNotAdmitAnotherFlowOrUndoReadiness() {
        assertEquals(
            StartupLanState.ChooseConnection,
            StartupLanState.ChooseConnection.next(StartupLanEvent.PermissionResult(true)),
        )
        assertEquals(
            StartupLanState.Ready,
            StartupLanState.Ready.next(StartupLanEvent.PermissionResult(false)),
        )
        assertEquals(
            StartupLanState.Ready,
            StartupLanState.Ready.next(StartupLanEvent.PermissionChecked(false)),
        )
    }

    @Test
    fun denialBlocksConnectionUntilRetryGrant() {
        var state = StartupLanState.Checking
        assertFalse(state.connectReady)
        state = state.next(StartupLanEvent.PermissionChecked(false))
        assertEquals(StartupLanState.Request, state)
        assertFalse(state.connectReady)
        state = state.next(StartupLanEvent.PermissionResult(false))
        assertEquals(StartupLanState.Denied, state)
        assertFalse(state.connectReady)
        state = state.next(StartupLanEvent.Retry)
        assertEquals(StartupLanState.Request, state)
        state = state.next(StartupLanEvent.PermissionResult(true))
        assertEquals(StartupLanState.Ready, state)
        assertTrue(state.connectReady)
    }

    @Test
    fun chooseConnectionAndBackDoNotAdmitNavigation() {
        var state = StartupLanState.Checking.next(StartupLanEvent.PermissionChecked(false))
        state = state.next(StartupLanEvent.PermissionResult(false))
        state = state.next(StartupLanEvent.ChooseConnection)
        assertEquals(StartupLanState.ChooseConnection, state)
        assertFalse(state.connectReady)
        state = state.next(StartupLanEvent.Back)
        assertEquals(StartupLanState.Denied, state)
        state = state.next(StartupLanEvent.ChooseConnection).next(StartupLanEvent.Connected)
        assertTrue(state.connectReady)
    }

    @Test
    fun alreadyGrantedAtStartupAdmitsWithoutRequest() {
        assertTrue(StartupLanState.Checking.next(StartupLanEvent.PermissionChecked(true)).connectReady)
    }

    @Test
    fun settingsResumeAdmitsOnlyDeniedAndDoesNotResetReady() {
        val denied = StartupLanState.Denied
        assertEquals(denied, denied.next(StartupLanEvent.Resume(false)))
        assertEquals(StartupLanState.Ready, denied.next(StartupLanEvent.Resume(true)))
        assertEquals(StartupLanState.Request, StartupLanState.Request.next(StartupLanEvent.Resume(true)))
        assertEquals(StartupLanState.Ready, StartupLanState.Ready.next(StartupLanEvent.Resume(false)))
    }
}
