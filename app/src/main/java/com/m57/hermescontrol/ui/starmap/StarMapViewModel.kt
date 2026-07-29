package com.m57.hermescontrol.ui.starmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.model.LearningGraphNode
import com.m57.hermescontrol.data.model.LearningNodeDetailResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class StarMapFilter {
    ALL,
    MEMORIES,
    SKILLS,
}

data class StarNodeUi(
    val id: String,
    val label: String,
    val kind: String,
    val category: String,
    val useCount: Int,
    val createdBy: String?,
    val timestamp: Long?,
    val pinned: Boolean,
    val memorySource: String?,
    val x: Float,
    val y: Float,
    val radius: Float,
    val connectedIds: List<String> = emptyList(),
)

data class StarEdgeUi(
    val sourceId: String,
    val targetId: String,
)

data class StarMapUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val nodes: List<StarNodeUi> = emptyList(),
    val edges: List<StarEdgeUi> = emptyList(),
    val selectedNodeId: String? = null,
    val filter: StarMapFilter = StarMapFilter.ALL,
    val searchQuery: String = "",
    val nodeDetail: LearningNodeDetailResponse? = null,
    val isLoadingDetail: Boolean = false,
)

class StarMapViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(StarMapUiState())
    val uiState: StateFlow<StarMapUiState> = _uiState.asStateFlow()

    init {
        loadGraph()
    }

    fun loadGraph() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val response =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getLearningGraph() }
                }

            when (response) {
                is NetworkResult.Success -> {
                    val data = response.data
                    val rawNodes = data.nodes
                    val rawEdges = data.edges

                    val processedNodes = computeSpatialLayout(rawNodes, rawEdges)
                    val processedEdges = rawEdges.map { StarEdgeUi(it.source, it.target) }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            nodes = processedNodes,
                            edges = processedEdges,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = response.error.message,
                        )
                    }
                }
            }
        }
    }

    fun selectNode(nodeId: String?) {
        _uiState.update { it.copy(selectedNodeId = nodeId, nodeDetail = null) }
        if (nodeId != null) {
            loadNodeDetail(nodeId)
        }
    }

    private fun loadNodeDetail(nodeId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDetail = true) }
            val response =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.getLearningNode(id = nodeId) }
                }
            when (response) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(isLoadingDetail = false, nodeDetail = response.data) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(isLoadingDetail = false) }
                }
            }
        }
    }

    fun setFilter(filter: StarMapFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    private fun computeSpatialLayout(
        rawNodes: List<LearningGraphNode>,
        rawEdges: List<com.m57.hermescontrol.data.model.LearningGraphEdge>,
    ): List<StarNodeUi> {
        if (rawNodes.isEmpty()) return emptyList()

        val adjMap = mutableMapOf<String, MutableList<String>>()
        rawEdges.forEach { edge ->
            adjMap.getOrPut(edge.source) { mutableListOf() }.add(edge.target)
            adjMap.getOrPut(edge.target) { mutableListOf() }.add(edge.source)
        }

        val memoryNodes = rawNodes.filter { it.kind == "memory" }
        val skillNodes = rawNodes.filter { it.kind != "memory" }

        val nodePositions = mutableMapOf<String, Pair<Float, Float>>()
        val center = 0f to 0f
        val rng = Random(42)

        memoryNodes.forEachIndexed { index, node ->
            val angle = (2.0 * Math.PI * index / (memoryNodes.size.coerceAtLeast(1))).toFloat() + rng.nextFloat() * 0.2f
            val radius = 180f + (index % 3) * 70f
            val x = center.first + radius * cos(angle)
            val y = center.second + radius * sin(angle)
            nodePositions[node.id] = x to y
        }

        val categories = skillNodes.map { it.category ?: "general" }.distinct()
        val categoryAngleStep = if (categories.isNotEmpty()) (2.0 * Math.PI / categories.size).toFloat() else 1f

        skillNodes.forEachIndexed { index, node ->
            val catIndex = categories.indexOf(node.category ?: "general").coerceAtLeast(0)
            val baseAngle = catIndex * categoryAngleStep
            val angleOffset = (rng.nextFloat() - 0.5f) * (categoryAngleStep * 0.7f)
            val angle = baseAngle + angleOffset
            val radius = 380f + (index % 4) * 90f
            val x = center.first + radius * cos(angle)
            val y = center.second + radius * sin(angle)
            nodePositions[node.id] = x to y
        }

        return rawNodes.map { raw ->
            val pos = nodePositions[raw.id] ?: (rng.nextFloat() * 400f - 200f to rng.nextFloat() * 400f - 200f)
            val baseRadius =
                when (raw.kind) {
                    "memory" -> 16f
                    "skill" -> 20f + (raw.useCount ?: 0).coerceAtMost(10) * 1.5f
                    else -> 18f
                }
            StarNodeUi(
                id = raw.id,
                label = raw.label,
                kind = raw.kind,
                category = raw.category ?: "general",
                useCount = raw.useCount ?: 0,
                createdBy = raw.createdBy,
                timestamp = raw.timestamp,
                pinned = raw.pinned ?: false,
                memorySource = raw.memorySource,
                x = pos.first,
                y = pos.second,
                radius = baseRadius,
                connectedIds = adjMap[raw.id] ?: emptyList(),
            )
        }
    }
}
