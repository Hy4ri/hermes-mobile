package com.m57.hermescontrol.data.model
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginInfo(
    val name: String,
    val description: String?,
    val version: String?,
    val source: String?,
    @SerialName("runtime_status") val runtimeStatus: String?,
    @SerialName("has_dashboard_manifest") val hasDashboardManifest: Boolean = false,
    @SerialName("dashboard_manifest") val dashboardManifest: PluginManifestData? = null,
    @SerialName("can_remove") val canRemove: Boolean = false,
    @SerialName("can_update_git") val canUpdateGit: Boolean = false,
    @SerialName("auth_required") val authRequired: Boolean = false,
    @SerialName("auth_command") val authCommand: String? = null,
    @SerialName("user_hidden") val userHidden: Boolean = false,
) {
    val enabled: Boolean
        get() = runtimeStatus.equals("enabled", ignoreCase = true)

    val installed: Boolean
        get() =
            runtimeStatus.equals("enabled", ignoreCase = true) ||
                runtimeStatus.equals("disabled", ignoreCase = true)
}

@Serializable
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
    @SerialName("has_api") val hasApi: Boolean = false,
    val source: String? = null,
)

@Serializable
data class PluginTabInfo(
    val path: String? = null,
    val position: String? = null,
    val override: String? = null,
    val hidden: Boolean = false,
)

@Serializable
data class DashboardPluginMeta(
    val name: String? = null,
    val label: String? = null,
    val description: String? = null,
    val tab: PluginTabInfo? = null,
)

@Serializable
data class PluginsHubProviders(
    @SerialName("memory_provider") val memoryProvider: String? = null,
    @SerialName("memory_options") val memoryOptions: List<ProviderOption> = emptyList(),
    @SerialName("context_engine") val contextEngine: String? = null,
    @SerialName("context_options") val contextOptions: List<ProviderOption> = emptyList(),
)

@Serializable
data class ProviderOption(
    val name: String,
    val description: String? = null,
)

@Serializable
data class PluginsHubResponse(
    val plugins: List<PluginInfo>,
    @SerialName("orphan_dashboard_plugins") val orphanDashboardPlugins: List<DashboardPluginMeta> = emptyList(),
    val providers: PluginsHubProviders? = null,
)

@Serializable
data class AgentPluginInstallBody(
    val identifier: String,
    val force: Boolean = false,
    val enable: Boolean = true,
)

@Serializable
data class PluginProvidersPutRequest(
    @SerialName("memory_provider") val memoryProvider: String? = null,
    @SerialName("context_engine") val contextEngine: String? = null,
)
