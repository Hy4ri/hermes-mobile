package com.m57.hermescontrol.ui.chat.components

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** withContext can discard a resource on cancelled dispatch back to the caller. */
internal suspend fun <T : AutoCloseable> openSpeechResource(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    create: () -> T,
): T {
    var owned: T? = null
    try {
        val result = withContext(dispatcher) { create().also { owned = it } }
        owned = null
        return result
    } finally {
        owned?.close()
    }
}
