package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
data class ProfilesResponse(
    val profiles: List<ProfileInfo>,
    val bot_mode_protocol: Boolean? = null,
)

@Serializable
data class ProfileInfo(
    val name: String,
    val path: String? = null,
    val is_default: Boolean? = null,
    val model: String? = null,
    val provider: String? = null,
    val has_env: Boolean? = null,
    val skill_count: Int? = null,
    val gateway_running: Boolean? = null,
    val description: String? = null,
    val display_name: String? = null,
    val description_auto: Boolean? = null,
    val has_avatar: Boolean? = null,
    val ui_meta: Map<String, JsonElement>? = null,
    val ui_meta_revisions: Map<String, Int>? = null,
    val canonical_session: CanonicalSessionInfo? = null,
    val last_session: ProfileSessionSummary? = null,
    val worker_session: ProfileWorkerSummary? = null,
) {
    fun botMeta(
        json: Json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
    ): BotRosterMeta? {
        val element = ui_meta?.get("hermes-bots") ?: return null
        return try {
            json.decodeFromJsonElement<BotRosterMeta>(element)
        } catch (_: Exception) {
            null
        }
    }

    val effectiveTitle: String
        get() =
            botMeta()?.title?.takeIf { it.isNotBlank() }
                ?: display_name?.takeIf { it.isNotBlank() }
                ?: name

    val effectiveDescription: String
        get() =
            botMeta()?.description?.takeIf { it.isNotBlank() }
                ?: description
                ?: ""

    val isHidden: Boolean
        get() = botMeta()?.hidden == true
}

@Serializable
data class CanonicalSessionInfo(
    val id: String,
    val resolved_id: String? = null,
    val root_title: String? = null,
    val title: String? = null,
    val preview: String? = null,
    val started_at: Double? = null,
    val last_active: Double? = null,
    val message_count: Int? = null,
)

@Serializable
data class ProfileSessionSummary(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val started_at: Double? = null,
    val last_active: Double? = null,
    val message_count: Int? = null,
)

@Serializable
data class ProfileWorkerSummary(
    val id: String,
    val source: String? = null,
    val title: String? = null,
    val last_active: Double? = null,
)

@Serializable
data class BotRosterMeta(
    val title: String? = null,
    val description: String? = null,
    val avatar: BotAvatarMeta? = null,
    val hidden: Boolean? = null,
    val groups: List<String>? = null,
    val group: String? = null,
    val created: Double? = null,
)

@Serializable
data class BotAvatarMeta(
    val shape: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val image_url: String? = null,
)

@Serializable
data class GroupChatSyncSnapshot(
    val version: Int? = null,
    val updatedAt: Long? = null,
    val rooms: Map<String, GroupChatRoomMeta>? = null,
    val deleted: Map<String, Long>? = null,
)

@Serializable
data class GroupChatRoomMeta(
    val id: String,
    val name: String? = null,
    val members: List<String>? = null,
    val picture: String? = null,
    val updatedAt: Long? = null,
    val createdAt: Long? = null,
)

@Serializable
data class ActiveProfileResponse(
    val active: String,
    val current: String? = null,
)

@Serializable
data class SetActiveProfileRequest(
    val name: String,
)

@Serializable
data class ProfileSoulResponse(
    val content: String,
)

@Serializable
data class UpdateProfileSoulRequest(
    val content: String,
)

@Serializable
data class UpdateProfileModelRequest(
    val provider: String,
    val model: String,
)

@Serializable
data class UpdateProfileDescriptionRequest(
    val description: String,
)

@Serializable
data class RenameProfileRequest(
    val new_name: String,
)

@Serializable
data class ProfileDescribeAutoRequest(
    val overwrite: Boolean = false,
)

@Serializable
data class ProfileDescribeAutoResponse(
    val ok: Boolean,
    val reason: String? = null,
    val description: String? = null,
    val description_auto: Boolean? = null,
)

@Serializable
data class ProfileSetupCommandResponse(
    val command: String,
)

@Serializable
data class CreateProfileRequest(
    val name: String,
    val description: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val mcp_servers: List<McpServerConfigInput>? = null,
    val keep_skills: Boolean? = null,
    val hub_skills: List<String>? = null,
    val clone_from: String? = null,
    val clone_all: Boolean? = null,
    val clone_from_default: Boolean? = null,
)

@Serializable
data class McpServerConfigInput(
    val name: String,
    val transport: String,
    val url: String? = null,
    val command: String? = null,
    val args: List<String>? = null,
)
