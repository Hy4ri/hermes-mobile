package com.m57.hermescontrol.data.model

import com.google.gson.annotations.SerializedName

data class PluginInfo(
    val name: String,
    val description: String?,
    val version: String?,
    val source: String?,
    @SerializedName("runtime_status") val runtimeStatus: String?,
    @SerializedName("has_dashboard_manifest") val hasDashboardManifest: Boolean = false,
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

data class PluginsHubResponse(
    val plugins: List<PluginInfo>,
)

data class TogglePluginRequest(
    val enabled: Boolean,
)

data class AgentPluginInstallBody(
    val identifier: String,
    val force: Boolean = false,
    val enable: Boolean = true,
)
