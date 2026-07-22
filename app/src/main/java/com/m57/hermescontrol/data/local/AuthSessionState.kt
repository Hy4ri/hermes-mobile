package com.m57.hermescontrol.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for app-wide auth session expiry latch.
 * When a REST call returns 401 (AuthExpired), [requireSignIn] is invoked,
 * triggering central navigation reset to [AuthLoginScreen] and WS disconnect.
 */
object AuthSessionState {
    private val _signInRequired = MutableStateFlow(false)
    val signInRequired: StateFlow<Boolean> = _signInRequired.asStateFlow()

    fun requireSignIn() {
        _signInRequired.value = true
    }

    fun markAuthenticated() {
        _signInRequired.value = false
    }

    fun clearSignInRequired() {
        _signInRequired.value = false
    }

    fun resetForTest() {
        _signInRequired.value = false
    }
}
