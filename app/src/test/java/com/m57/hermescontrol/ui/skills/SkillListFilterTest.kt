package com.m57.hermescontrol.ui.skills

import com.m57.hermescontrol.data.model.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillListFilterTest {
    private val sampleSkills =
        listOf(
            Skill(
                name = "code-review",
                category = "dev",
                description = "Automated code review assistant",
                enabled = true,
                source = "built-in",
            ),
            Skill(
                name = "deploy-bot",
                category = "ops",
                description = "Deploy to production environments",
                enabled = false,
                source = "hub",
            ),
            Skill(
                name = "image-gen",
                category = "creative",
                description = "Generate creative assets",
                enabled = true,
                source = "hub",
            ),
        )

    @Test
    fun `filterSkills empty query and all statuses returns all`() {
        val result =
            SkillListFilter.filterSkills(
                skills = sampleSkills,
                query = "",
                selectedStatus = SkillFilter.ALL_STATUSES,
                selectedCategory = CATEGORY_ALL,
                sourceFilter = null,
            )
        assertEquals(3, result.size)
    }

    @Test
    fun `filterSkills filters by search query on name and description`() {
        val nameResult =
            SkillListFilter.filterSkills(
                skills = sampleSkills,
                query = "code",
                selectedStatus = SkillFilter.ALL_STATUSES,
                selectedCategory = CATEGORY_ALL,
                sourceFilter = null,
            )
        assertEquals(1, nameResult.size)
        assertEquals("code-review", nameResult[0].name)

        val descResult =
            SkillListFilter.filterSkills(
                skills = sampleSkills,
                query = "production",
                selectedStatus = SkillFilter.ALL_STATUSES,
                selectedCategory = CATEGORY_ALL,
                sourceFilter = null,
            )
        assertEquals(1, descResult.size)
        assertEquals("deploy-bot", descResult[0].name)
    }

    @Test
    fun `filterSkills filters by enabled and disabled status`() {
        val enabledResult =
            SkillListFilter.filterSkills(
                skills = sampleSkills,
                query = "",
                selectedStatus = SkillFilter.ENABLED,
                selectedCategory = CATEGORY_ALL,
                sourceFilter = null,
            )
        assertEquals(2, enabledResult.size)
        assertTrue(enabledResult.all { it.enabled })

        val disabledResult =
            SkillListFilter.filterSkills(
                skills = sampleSkills,
                query = "",
                selectedStatus = SkillFilter.DISABLED,
                selectedCategory = CATEGORY_ALL,
                sourceFilter = null,
            )
        assertEquals(1, disabledResult.size)
        assertEquals("deploy-bot", disabledResult[0].name)
    }

    @Test
    fun `filterSkills filters by category and source`() {
        val catResult =
            SkillListFilter.filterSkills(
                skills = sampleSkills,
                query = "",
                selectedStatus = SkillFilter.ALL_STATUSES,
                selectedCategory = "ops",
                sourceFilter = null,
            )
        assertEquals(1, catResult.size)
        assertEquals("deploy-bot", catResult[0].name)

        val sourceResult =
            SkillListFilter.filterSkills(
                skills = sampleSkills,
                query = "",
                selectedStatus = SkillFilter.ALL_STATUSES,
                selectedCategory = CATEGORY_ALL,
                sourceFilter = "built-in",
            )
        assertEquals(1, sourceResult.size)
        assertEquals("code-review", sourceResult[0].name)
    }

    @Test
    fun `extractCategories and extractSources return distinct sorted values`() {
        val categories = SkillListFilter.extractCategories(sampleSkills)
        assertEquals(listOf("creative", "dev", "ops"), categories)

        val sources = SkillListFilter.extractSources(sampleSkills)
        assertEquals(listOf("built-in", "hub"), sources)
    }
}
