package com.m57.hermescontrol.ui.model.components

import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1697: pinned picker rows carry the provider under the model name; the label
 * prefers the catalog's display name and falls back to the slug.
 */
class PinnedProviderLabelTest {
    private val catalog =
        mapOf(
            "nous" to ModelProvider(slug = "nous", name = "Nous Portal"),
        )

    @Test
    fun usesProviderDisplayNameWhenTheCatalogHasIt() {
        assertEquals(
            "Nous Portal",
            pinnedProviderLabel(PinnedModel("nous", "deepseek-v4.1-flash"), catalog),
        )
    }

    @Test
    fun fallsBackToSlugWhileTheCatalogIsLoading() {
        assertEquals(
            "nous",
            pinnedProviderLabel(PinnedModel("nous", "deepseek-v4.1-flash"), emptyMap()),
        )
    }

    @Test
    fun fallsBackToSlugWhenTheProviderWasRemoved() {
        assertEquals(
            "retired",
            pinnedProviderLabel(PinnedModel("retired", "gpt-4"), catalog),
        )
    }

    @Test
    fun fallsBackToSlugWhenTheNameIsBlank() {
        assertEquals(
            "nous",
            pinnedProviderLabel(
                PinnedModel("nous", "deepseek-v4.1-flash"),
                mapOf("nous" to ModelProvider(slug = "nous", name = "")),
            ),
        )
    }

    @Test
    fun distinguishesTheSameModelNameAcrossTwoProviders() {
        val bySlug =
            mapOf(
                "alpha" to ModelProvider(slug = "alpha", name = "Alpha"),
                "beta" to ModelProvider(slug = "beta", name = "Beta"),
            )
        val alpha = pinnedProviderLabel(PinnedModel("alpha", "deepseek-v4.1-flash"), bySlug)
        val beta = pinnedProviderLabel(PinnedModel("beta", "deepseek-v4.1-flash"), bySlug)

        assertEquals("Alpha", alpha)
        assertEquals("Beta", beta)
    }
}
