package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.content.Intent
import com.m57.hermescontrol.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaExportHelper {
    suspend fun saveMediaToDownloads(
        context: Context,
        uri: String,
        fallbackMime: String = "application/octet-stream",
        displayName: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val resolved = MediaBytesResolver.resolve(context, uri, fallbackMime)
            when (resolved) {
                is MediaBytesResolver.Result.Bytes -> {
                    val baseName = displayName?.takeIf { it.isNotBlank() } ?: "hermes-media"
                    val name =
                        if (extractFileExtension(baseName).isEmpty()) {
                            "$baseName.${resolved.extension}"
                        } else {
                            baseName
                        }
                    val saved =
                        MediaImageStore.saveToDownloads(
                            context = context,
                            bytes = resolved.bytes,
                            displayName = name,
                            mimeType = resolved.mimeType,
                        )
                    if (saved != null) {
                        context.getString(R.string.media_player_saved)
                    } else {
                        context.getString(R.string.media_player_save_failed)
                    }
                }

                is MediaBytesResolver.Result.Error -> {
                    context.getString(R.string.media_player_load_failed, resolved.message)
                }
            }
        }

    suspend fun shareMedia(
        context: Context,
        uri: String,
        fallbackMime: String = "application/octet-stream",
        displayName: String? = null,
    ): Intent? =
        withContext(Dispatchers.IO) {
            val resolved = MediaBytesResolver.resolve(context, uri, fallbackMime)
            when (resolved) {
                is MediaBytesResolver.Result.Bytes -> {
                    val baseName = displayName?.takeIf { it.isNotBlank() } ?: "hermes-media"
                    val name =
                        if (extractFileExtension(baseName).isEmpty()) {
                            "$baseName.${resolved.extension}"
                        } else {
                            baseName
                        }
                    MediaImageStore.buildShareIntent(
                        context = context,
                        bytes = resolved.bytes,
                        displayName = name,
                        mimeType = resolved.mimeType,
                    )
                }

                is MediaBytesResolver.Result.Error -> {
                    null
                }
            }
        }
}
