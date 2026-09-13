package com.m57.hermescontrol.ui.skills

import com.m57.hermescontrol.data.model.Skill

object SkillListFilter {
    /**
     * Filters skills by search query, enabled/disabled status, category, and source.
     */
    fun filterSkills(
        skills: List<Skill>,
        query: String,
        selectedStatus: SkillFilter,
        selectedCategory: String?,
        sourceFilter: String?,
    ): List<Skill> =
        skills.filter { skill ->
            val matchesQuery =
                query.isBlank() ||
                    skill.name.contains(query, ignoreCase = true) ||
                    skill.description?.contains(query, ignoreCase = true) == true
            val matchesStatus =
                when (selectedStatus) {
                    SkillFilter.ALL_STATUSES -> true
                    SkillFilter.ENABLED -> skill.enabled
                    SkillFilter.DISABLED -> !skill.enabled
                }
            val matchesCategory =
                selectedCategory == null ||
                    selectedCategory == CATEGORY_ALL ||
                    skill.category == selectedCategory
            val matchesSource = sourceFilter == null || skill.source == sourceFilter

            matchesQuery && matchesStatus && matchesCategory && matchesSource
        }

    fun extractCategories(skills: List<Skill>): List<String> = skills.mapNotNull { it.category }.distinct().sorted()

    fun extractSources(skills: List<Skill>): List<String> = skills.mapNotNull { it.source }.distinct().sorted()
}
