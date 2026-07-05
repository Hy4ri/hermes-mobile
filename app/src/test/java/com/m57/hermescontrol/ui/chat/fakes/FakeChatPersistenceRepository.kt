package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.ui.chat.ChatPersistenceRepository

/**
 * In-memory [ChatPersistenceRepository] for tests.
 *
 * Wraps [FakeChatMessageDao] so tests can read/write messages
 * without Room or SQLCipher. DAO is exposed for direct inspection.
 *
 * Usage:
 * ```
 * val fakeRepo = FakeChatPersistenceRepository()
 * fakeRepo.dao.addMessageDirect(someEntity)
 * viewModel.injectRepo(fakeRepo)
 * ```
 */
class FakeChatPersistenceRepository(
    val dao: FakeChatMessageDao = FakeChatMessageDao(),
) : ChatPersistenceRepository(dao) {
    /** Clear all stored messages. */
    fun clear() = dao.clear()
}
