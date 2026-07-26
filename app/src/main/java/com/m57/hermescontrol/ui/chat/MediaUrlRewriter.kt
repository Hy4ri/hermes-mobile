package com.m57.hermescontrol.ui.chat

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Issue #724: turn host-path MEDIA: images into gateway-served URLs.
 *
 * The gateway's WebSocket stream delivers the raw `MEDIA:<path>` directive the
 * desktop app resolves via `mediaExternalUrl` — it rewrites the path to the
 * authenticated `/api/files/download?path=<enc>&token=<enc>` endpoint, which
 * streams the host file's bytes over HTTP. The mobile renderer (`MarkdownText`)
 * shows http(s) `![]()` images via Coil, so rewriting the directive into a
 * markdown image tag makes agent-sent images appear on mobile too — including
 * on a *remote* phone (real HTTP, unlike a host-local file read).
 *
 * Mobile-only, backend untouched. Mirrors `apps/desktop/src/lib/media.ts`
 * `mediaExternalUrl()`. The pure logic lives here so it can be unit-tested
 * without an Android context.
 */
internal object MediaUrlRewriter {
    /** Absolute (or ~/ ) host path + known image extension, optional quotes/backticks. */
    private val MEDIA_TAG_RE =
        Regex(
            """[`"']?MEDIA:\s*((?:~|/|/[A-Za-z]:)[^\s`"')]*?\.(png|jpe?g|gif|webp|bmp))[`"']?""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Pure transform: replace every `MEDIA:<path>` directive with
     * `![image](<baseUrl>/api/files/download?path=<enc>&token=<enc>)` so the
     * renderer can fetch the host file over HTTP. Directives whose path is not
     * an absolute/~/ image path are left untouched.
     *
     * @param text      message content that may contain MEDIA: directives
     * @param baseUrl   gateway REST base url, e.g. `https://host:9119`
     * @param token     gateway session token (used as the `?token=` query param)
     */
    fun rewriteMediaToGatewayUrls(
        text: String,
        baseUrl: String,
        token: String,
    ): String {
        if (!text.contains("MEDIA:")) return text
        val encPath = { p: String -> URLEncoder.encode(p, StandardCharsets.UTF_8.name()) }
        val encToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
        val endpoint = baseUrl.trimEnd('/') + "/api/files/download"
        return MEDIA_TAG_RE.replace(text) { m ->
            val rawPath = m.groupValues[1]
            val path = resolveMediaPath(rawPath)
            if (path == null) {
                m.value // not an absolute/~/ image path — leave as-is
            } else {
                "![image]($endpoint?path=${encPath(path)}&token=$encToken)"
            }
        }
    }

    /** Expand ~/ and verify the path is absolute. Null if not an absolute path. */
    fun resolveMediaPath(raw: String): String? {
        val trimmed =
            raw
                .trim()
                .removeSurrounding("`")
                .removeSurrounding("\"")
                .removeSurrounding("'")
        val expanded =
            if (trimmed.startsWith("~")) {
                val home = System.getenv("HOME") ?: return null
                home + trimmed.removePrefix("~")
            } else {
                trimmed
            }
        if (!expanded.startsWith("/") &&
            !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(expanded)
        ) {
            return null
        }
        return expanded
    }
}
