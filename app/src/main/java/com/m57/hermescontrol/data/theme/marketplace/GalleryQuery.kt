package com.m57.hermescontrol.data.theme.marketplace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** VS Code Gallery ExtensionQuery wire models (t_5316ccb7).
 *
 * Field names are the gallery's own camelCase/PascalCase — always accessed
 * through the repository's dedicated `Json { ignoreUnknownKeys = true }`
 * instance, never the app's snake_case [OkHttpProvider.json], so no
 * naming-strategy mangling applies.
 *
 * FilterType constants (undocumented but stable, ported verbatim from
 * `apps/desktop/electron/vscode-marketplace.ts`):
 * - 5 = Category (`Themes`), 7 = ExtensionName, 8 = Target
 *   (`Microsoft.VisualStudio.Code`), 10 = SearchText, 12 = ExcludeWithFlags
 *   (`4096` = Unpublished).
 */
@Serializable
internal data class GalleryCriterion(
    @SerialName("filterType") val filterType: Int,
    @SerialName("value") val value: String,
)

@Serializable
internal data class GalleryFilter(
    @SerialName("criteria") val criteria: List<GalleryCriterion>,
    @SerialName("pageNumber") val pageNumber: Int = 1,
    @SerialName("pageSize") val pageSize: Int,
    @SerialName("sortBy") val sortBy: Int = 4,
    @SerialName("sortOrder") val sortOrder: Int = 0,
)

@Serializable
internal data class GalleryQueryPayload(
    @SerialName("filters") val filters: List<GalleryFilter>,
    @SerialName("flags") val flags: Int,
)

internal fun gallerySearchPayload(
    query: String,
    pageSize: Int,
    pageNumber: Int = 1,
): GalleryQueryPayload {
    val criteria =
        buildList {
            add(GalleryCriterion(8, "Microsoft.VisualStudio.Code"))
            add(GalleryCriterion(5, "Themes"))
            add(GalleryCriterion(12, "4096"))
            val text = query.trim()
            if (text.isNotEmpty()) {
                add(GalleryCriterion(10, text))
            }
        }
    return GalleryQueryPayload(
        filters = listOf(GalleryFilter(criteria = criteria, pageNumber = pageNumber, pageSize = pageSize)),
        flags = 772,
    )
}

/** Per-extension VSIX resolve payload: flags 914 includes files + asset URIs. */
internal fun galleryResolvePayload(extensionId: String): GalleryQueryPayload =
    GalleryQueryPayload(
        filters =
            listOf(
                GalleryFilter(
                    criteria = listOf(GalleryCriterion(7, extensionId)),
                    pageNumber = 1,
                    pageSize = 1,
                    sortBy = 0,
                    sortOrder = 0,
                ),
            ),
        flags = 914,
    )

// ── Response (only the fields the catalog actually reads) ────────────────

@Serializable
internal data class GalleryResponse(
    @SerialName("results") val results: List<GalleryResult> = emptyList(),
)

@Serializable
internal data class GalleryResult(
    @SerialName("extensions") val extensions: List<GalleryExtension> = emptyList(),
)

@Serializable
internal data class GalleryExtension(
    @SerialName("extensionName") val extensionName: String = "",
    @SerialName("displayName") val displayName: String = "",
    @SerialName("shortDescription") val shortDescription: String = "",
    @SerialName("publisher") val publisher: GalleryPublisher? = null,
    @SerialName("statistics") val statistics: List<GalleryStatistic> = emptyList(),
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("versions") val versions: List<GalleryVersion> = emptyList(),
)

@Serializable
internal data class GalleryPublisher(
    @SerialName("publisherName") val publisherName: String = "",
    @SerialName("displayName") val displayName: String = "",
)

@Serializable
internal data class GalleryStatistic(
    @SerialName("statisticName") val statisticName: String = "",
    @SerialName("value") val value: Double = 0.0,
)

@Serializable
internal data class GalleryVersion(
    @SerialName("files") val files: List<GalleryFile> = emptyList(),
)

@Serializable
internal data class GalleryFile(
    @SerialName("assetType") val assetType: String = "",
    @SerialName("source") val source: String = "",
)
