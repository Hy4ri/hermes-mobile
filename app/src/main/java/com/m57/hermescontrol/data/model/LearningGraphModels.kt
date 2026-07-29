package com.m57.hermescontrol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data structures for the Hermes agent self-improvement / learning graph endpoint
 * GET /api/learning/graph
 */
@Serializable
data class LearningGraphNode(
    val id: String,
    val label: String,
    val kind: String, // "skill" | "memory"
    val timestamp: Long? = null,
    val category: String? = null,
    @SerialName("useCount") val useCount: Int = 0,
    val state: String? = null,
    @SerialName("createdBy") val createdBy: String? = null,
    val pinned: Boolean = false,
)

@Serializable
data class LearningGraphEdge(
    val source: String,
    val target: String,
)

@Serializable
data class LearningGraphMemoryCard(
    val source: String,
    val timestamp: Long? = null,
    val title: String,
    val body: String,
)

@Serializable
data class LearningGraphResponse(
    val nodes: List<LearningGraphNode> = emptyList(),
    val edges: List<LearningGraphEdge> = emptyList(),
    val memory: List<LearningGraphMemoryCard> = emptyList(),
)
