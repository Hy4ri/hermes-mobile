package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.data.local.SlashUsageStore
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [SlashUsageStore] for ChatViewModel tests (issue #865).
 *
 * Mirrors the real store's semantics: [recordUse] bumps the count and
 * [counts] emits the current map. The real store needs an Android Context
 * (DataStore), which unit tests don't have — the relaxed mock is a dummy
 * the overrides never touch (same pattern as the mocked Application in
 * ChatViewModelTest).
 */
class FakeSlashUsageStore : SlashUsageStore(mockk(relaxed = true)) {
    private val countsFlow = MutableStateFlow<Map<String, Int>>(emptyMap())

    override fun counts(): Flow<Map<String, Int>> = countsFlow

    override suspend fun recordUse(command: String) {
        countsFlow.update { it + (command to ((it[command] ?: 0) + 1)) }
    }
}
