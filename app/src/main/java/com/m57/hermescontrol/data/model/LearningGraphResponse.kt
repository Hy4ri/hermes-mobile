package com.m57.hermescontrol.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LearningGraphResponse(
    val nodes: List<LearningGraphNode> = emptyList(),
    val edges: List<LearningGraphEdge> = emptyList(),
    val clusters: List<LearningCluster> = emptyList(),
    val memory: List<LearningMemoryCard> = emptyList(),
    val stats: Map<String, JsonElement>? = null,
)

@Serializable
data class LearningGraphNode(
    val id: String,
    val label: String,
    val kind: String = "skill",
    val timestamp: Long? = null,
    val category: String? = null,
    val useCount: Int? = 0,
    val state: String? = null,
    val createdBy: String? = null,
    val pinned: Boolean? = false,
    val memorySource: String? = null,
)

@Serializable
data class LearningGraphEdge(
    val source: String,
    val target: String,
)

@Serializable
data class LearningCluster(
    val category: String,
    val count: Int,
)

@Serializable
data class LearningMemoryCard(
    val source: String,
    val timestamp: Long? = null,
    val title: String,
    val body: String,
)

@Serializable
data class LearningNodeDetailResponse(
    val ok: Boolean = true,
    val id: String? = null,
    val kind: String? = null,
    val title: String? = null,
    val content: String? = null,
    val category: String? = null,
    val timestamp: Long? = null,
    val memorySource: String? = null,
    val related: List<String>? = null,
    val message: String? = null,
)
