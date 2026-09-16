package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.UpdateTaskBody

private val ALL_REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

fun supportedKanbanReasoningEfforts(
    supportsReasoning: Boolean,
    canDisableReasoning: Boolean,
): List<String> =
    if (!supportsReasoning) {
        listOf("")
    } else if (canDisableReasoning) {
        ALL_REASONING_EFFORTS
    } else {
        ALL_REASONING_EFFORTS.filterNot { it == "none" }
    }

/**
 * Task model override matching desktop's TaskModelOverride contract:
 * - model: model name or ID
 * - provider: provider name or ID
 * - effort: "" (inherit), "none" (explicitly disabled), or reasoning effort string ("low", "medium", "high")
 */
data class KanbanModelOverride(
    val model: String = "",
    val provider: String = "",
    val effort: String = "",
) {
    val isInherited: Boolean
        get() = model.isBlank() && provider.isBlank() && effort.isBlank()

    fun displayLabel(inheritLabel: String = "Inherit profile"): String {
        if (isInherited) return inheritLabel
        val trimmedModel = model.trim()
        val trimmedProvider = provider.trim()
        val trimmedEffort = effort.trim()

        val base =
            when {
                trimmedModel.isNotEmpty() && trimmedProvider.isNotEmpty() -> "$trimmedProvider: $trimmedModel"
                trimmedModel.isNotEmpty() -> trimmedModel
                else -> inheritLabel
            }

        val effortPart =
            when (trimmedEffort.lowercase()) {
                "none" -> "None"
                "low" -> "Low"
                "medium" -> "Medium"
                "high" -> "High"
                "" -> ""
                else -> trimmedEffort.replaceFirstChar { it.uppercase() }
            }

        return if (effortPart.isNotEmpty() && base != inheritLabel) {
            "$base · $effortPart"
        } else if (effortPart.isNotEmpty()) {
            "Effort: $effortPart"
        } else {
            base
        }
    }

    fun toUpdateTaskBody(): UpdateTaskBody {
        val trimmedModel = model.trim()
        val trimmedProvider = provider.trim()
        val trimmedEffort = effort.trim()

        return UpdateTaskBody(
            modelOverride = trimmedModel.ifEmpty { null },
            providerOverride = if (trimmedModel.isNotEmpty() && trimmedProvider.isNotEmpty()) trimmedProvider else null,
            clearModelOverride = trimmedModel.isEmpty(),
            reasoningEffort = trimmedEffort.ifEmpty { null },
            clearReasoningEffort = trimmedEffort.isEmpty(),
        )
    }

    companion object {
        val EMPTY = KanbanModelOverride()

        fun fromTask(
            model: String?,
            provider: String?,
            effort: String?,
        ): KanbanModelOverride =
            KanbanModelOverride(
                model = model?.trim().orEmpty(),
                provider = provider?.trim().orEmpty(),
                effort = effort?.trim().orEmpty(),
            )
    }
}
