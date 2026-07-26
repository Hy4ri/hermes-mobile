package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single entry returned by `GET /api/files` — a file or directory living
 * under the gateway's managed-files root.
 *
 * Mirrors the backend `_managed_file_entry()` dict exactly
 * (hermes_cli/web_server.py). `mtime` is an epoch float on the wire; the
 * mobile model declares it `Double?` so a fractional value does not throw a
 * kotlinx decoding error (see dashboard-tab-to-android pitfalls: numeric
 * type drift).
 */
@Serializable
data class ManagedFileEntry(
    val name: String = "",
    val path: String = "",
    @SerialName("is_directory")
    val isDirectory: Boolean = false,
    val size: Long? = null,
    val mtime: Double? = null,
    @SerialName("mime_type")
    val mimeType: String? = null,
)

/**
 * Response of `GET /api/files` (directory listing with navigation metadata).
 *
 * `parent` is `null` at the managed root (or when a locked root hides the
 * parent). `entries` is already server-sorted directories-first, then
 * case-insensitive name.
 */
@Serializable
data class ManagedFilesListResponse(
    val path: String = "",
    val parent: String? = null,
    val entries: List<ManagedFileEntry> = emptyList(),
    val root: String? = null,
    @SerialName("locked_root")
    val lockedRoot: String? = null,
    @SerialName("can_change_path")
    val canChangePath: Boolean = false,
)

/** Response of `GET /api/files/read` — base64 data URL of a file's bytes. */
@Serializable
data class ManagedFileRead(
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    @SerialName("mime_type")
    val mimeType: String = "application/octet-stream",
    @SerialName("data_url")
    val dataUrl: String = "",
    val root: String? = null,
    @SerialName("locked_root")
    val lockedRoot: String? = null,
    @SerialName("can_change_path")
    val canChangePath: Boolean = false,
)

/** Generic `{"ok": true}`-style acknowledgement used by upload/mkdir/delete. */
@Serializable
data class ManagedFileActionResponse(
    val ok: Boolean = false,
    val path: String? = null,
    val entry: ManagedFileEntry? = null,
    val root: String? = null,
    @SerialName("locked_root")
    val lockedRoot: String? = null,
    @SerialName("can_change_path")
    val canChangePath: Boolean = false,
)

/** Request body for `POST /api/files/upload` (base64 data URL form). */
@Serializable
data class ManagedFileUpload(
    val path: String,
    val data_url: String,
    val overwrite: Boolean = false,
)

/** Request body for `POST /api/files/mkdir`. */
@Serializable
data class ManagedDirectoryCreate(
    val path: String,
)

/** Request body for `DELETE /api/files`. */
@Serializable
data class ManagedFileDelete(
    val path: String,
    val recursive: Boolean = false,
)
