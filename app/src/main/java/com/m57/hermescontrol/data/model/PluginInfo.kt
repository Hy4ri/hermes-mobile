package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class PluginInfo(
    val name: String,
    val description: String?,
    val version: String?,
    val source: String?,
    @SerializedName("runtime_status") val runtimeStatus: String?,
    @SerializedName("has_dashboard_manifest") val hasDashboardManifest: Boolean = false,
    @SerializedName("dashboard_manifest") val dashboardManifest: PluginManifestData? = null,
    @SerializedName("can_remove") val canRemove: Boolean = false,
    @SerializedName("can_update_git") val canUpdateGit: Boolean = false,
    @SerializedName("auth_required") val authRequired: Boolean = false,
    @SerializedName("auth_command") val authCommand: String? = null,
    @SerializedName("user_hidden") val userHidden: Boolean = false,
) {
    val enabled: Boolean
        get() = runtimeStatus.equals("enabled", ignoreCase = true)

    val installed: Boolean
        get() =
            runtimeStatus.equals("enabled", ignoreCase = true) ||
                runtimeStatus.equals("disabled", ignoreCase = true)
}

data class PluginManifestData(
    val name: String? = null,
    val label: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val version: String? = null,
    val tab: PluginTabInfo? = null,
    val slots: List<String>? = null,
    val entry: String? = null,
    val css: String? = null,
    @SerializedName("has_api") val hasApi: Boolean = false,
    val source: String? = null,
)

data class PluginTabInfo(
    val path: String? = null,
    val position: String? = null,
    val override: String? = null,
    val hidden: Boolean = false,
)

data class DashboardPluginMeta(
    val name: String? = null,
    val label: String? = null,
    val description: String? = null,
    val tab: PluginTabInfo? = null,
)

data class PluginsHubProviders(
    @SerializedName("memory_provider") val memoryProvider: String? = null,
    @SerializedName("memory_options") val memoryOptions: List<ProviderOption> = emptyList(),
    @SerializedName("context_engine") val contextEngine: String? = null,
    @SerializedName("context_options") val contextOptions: List<ProviderOption> = emptyList(),
)

data class ProviderOption(
    val name: String,
    val description: String? = null,
)

data class PluginsHubResponse(
    val plugins: List<PluginInfo>,
    @SerializedName("orphan_dashboard_plugins") val orphanDashboardPlugins: List<DashboardPluginMeta> = emptyList(),
    val providers: PluginsHubProviders? = null,
)

data class AgentPluginInstallBody(
    val identifier: String,
    val force: Boolean = false,
    val enable: Boolean = true,
)

data class PluginProvidersPutRequest(
    @SerializedName("memory_provider") val memoryProvider: String? = null,
    @SerializedName("context_engine") val contextEngine: String? = null,
)
