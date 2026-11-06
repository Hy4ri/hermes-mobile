package com.m57.hermescontrol.data.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class PluginSettingFieldType {
    @SerialName("string")
    STRING,

    @SerialName("number")
    NUMBER,

    @SerialName("boolean")
    BOOLEAN,

    @SerialName("enum")
    ENUM,

    @SerialName("secret")
    SECRET,

    @SerialName("json")
    JSON,
}

@Serializable
data class PluginSettingField(
    val key: String,
    val type: PluginSettingFieldType,
    val label: String,
    val description: String = "",
    val required: Boolean = false,
    val value: JsonElement? = null,
    val default: JsonElement? = null,
    val choices: List<String>? = null,
    val env: String? = null,
    @SerialName("has_value") val hasValue: Boolean? = null,
)

@Serializable
enum class PluginServerState {
    @SerialName("connected")
    CONNECTED,

    @SerialName("app_not_running")
    APP_NOT_RUNNING,

    @SerialName("endpoint_unavailable")
    ENDPOINT_UNAVAILABLE,

    @SerialName("no_interactive_session")
    NO_INTERACTIVE_SESSION,

    @SerialName("version_too_old")
    VERSION_TOO_OLD,

    @SerialName("missing_app")
    MISSING_APP,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class PluginServerRow(
    val name: String,
    val state: PluginServerState,
    val sentence: String = "",
)

@Serializable
data class AgentPluginRow(
    val name: String,
    val key: String? = null,
    val version: String = "",
    val description: String = "",
    val source: String = "",
    val status: String = "",
    val portable: Boolean = false,
    @SerialName("install_dir") val installDir: String = "",
    @SerialName("has_desktop_half") val hasDesktopHalf: Boolean = false,
    val servers: List<PluginServerRow> = emptyList(),
    @SerialName("settings_schema") val settingsSchema: List<PluginSettingField>? = null,
    @SerialName("catalog_name") val catalogName: String? = null,
    @SerialName("catalog_tier") val catalogTier: String? = null,
    @SerialName("installed_sha") val installedSha: String? = null,
    @SerialName("catalog_sha") val catalogSha: String? = null,
    @SerialName("catalog_version") val catalogVersion: String? = null,
    @SerialName("update_available") val updateAvailable: Boolean? = null,
    @SerialName("pinned_sha") val pinnedSha: String? = null,
)

@Serializable
data class PluginsManageSettingsResult(
    val ok: Boolean = false,
    val name: String? = null,
    val written: List<String>? = null,
    val plugin: AgentPluginRow? = null,
    val error: String? = null,
)

/** One plugin touched by the unattended `update_all` sweep (`plugins.manage`). */
@Serializable
data class PluginUpdateAllRow(
    val name: String,
    val status: String,
    val detail: String = "",
) {
    val isNeedsReview: Boolean
        get() = status == "needs_review"
}

/** One installed plugin the sweep left alone, with the backend's reason. */
@Serializable
data class PluginUpdateAllSkip(
    val name: String,
    val reason: String = "",
)

/**
 * Result of `plugins.manage update_all`: nothing is accepted on the user's behalf, so a widened
 * pin or caution scan comes back as `needs_review` and the plugin's own update walks that review.
 */
@Serializable
data class PluginUpdateAllResult(
    val results: List<PluginUpdateAllRow> = emptyList(),
    val skipped: List<PluginUpdateAllSkip> = emptyList(),
    val current: Int = 0,
)

/** The active profile's auto-update switch (`plugins.auto_apply`) and check cadence. */
@Serializable
data class PluginAutoUpdateState(
    @SerialName("auto_apply") val autoApply: Boolean = false,
    @SerialName("check_hours") val checkHours: Int = 24,
)
