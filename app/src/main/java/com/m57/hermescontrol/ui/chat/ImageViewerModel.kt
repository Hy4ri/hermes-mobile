package com.m57.hermescontrol.ui.chat

/**
 * A single image available for the full-screen viewer (issue #723).
 *
 * @param model The Coil model already rendered in the bubble — one of:
 *   - `data:image/...;base64,...` — agent-delivered inline media,
 *   - `content://...` — a locally-picked user image,
 *   - `http(s)://...` — gateway-served URL (carries the `?token=` auth param
 *     the same way the desktop/client already fetches it; no backend change).
 * @param name Preferred file name for Save/Share. When blank the viewer falls
 *   back to a timestamped name derived from the resolved image type.
 * @param mimeType Best-known MIME type (e.g. an attachment's `mimeType`). For
 *   markdown `![alt](url)` images this is unknown up-front and is refined from
 *   the resolved bytes at save/share time.
 */
data class ImageViewerModel(
    val model: String,
    val name: String = "",
    val mimeType: String = "image/*",
)
