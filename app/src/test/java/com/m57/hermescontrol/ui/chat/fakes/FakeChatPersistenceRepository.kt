package com.m57.hermescontrol.ui.chat.fakes

import com.m57.hermescontrol.ui.chat.ChatPersistenceRepository

/**
 * In-memory [ChatPersistenceRepository] for tests.
 *
 * Wraps [FakeChatMessageDao] so tests can read/write messages
 * without Room or SQLCipher. Pass to [ChatViewModel]'s `repo`
 * constructor parameter in tests.
 *
 * ```
 * val fakeRepo = FakeChatPersistenceRepository()
 * fakeRepo.dao.addMessageDirect(someEntity)
 * val vm = ChatViewModel(app, startCleanup, fakeRepo)
 * ```
 */
class FakeChatPersistenceRepository(
    val dao: FakeChatMessageDao = FakeChatMessageDao(),
) : ChatPersistenceRepository(dao) {
    /** Clear all stored messages. */
    fun clear() = dao.clear()
}
