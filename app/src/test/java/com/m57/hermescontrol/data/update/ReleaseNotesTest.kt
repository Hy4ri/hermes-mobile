package com.m57.hermescontrol.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun generatedNotes_groupByTypeAndHideInternalChanges() {
        val notes = parseReleaseNotes(V3_4_0)

        assertEquals(
            listOf(
                ReleaseNoteSection(
                    group = ReleaseNoteGroup.FIXES,
                    title = null,
                    items =
                        listOf(
                            "Seal reasoning-only segment at tool.start",
                            "Retire stale typing tail on idle reconciliation",
                            "Switch models via session.mutate and drop the /model bubble",
                            "Send active profile on session-bound RPCs",
                        ),
                ),
            ),
            notes.sections,
        )
        assertEquals("chore entry is hidden but counted", 1, notes.internalChanges)
        assertEquals(listOf("Hy4ri", "Erfan817", "trigor74"), notes.contributors)
        assertFalse(notes.isEmpty)
    }

    @Test
    fun generatedNotes_orderFeaturesBeforeFixesAndStripIssueRefs() {
        val notes = parseReleaseNotes(V3_3_0)

        assertEquals(
            listOf(ReleaseNoteGroup.FEATURES, ReleaseNoteGroup.FIXES),
            notes.sections.map { it.group },
        )
        assertEquals(listOf("Recolor launcher icon to the Default theme"), notes.sections[0].items)
        assertEquals(
            "trailing (#1824) issue ref is dropped",
            listOf("Show the clarify answer bubble on submit", "Align reasoning card with tool bubbles"),
            notes.sections[1].items,
        )
        assertEquals("chore, ci and two docs entries", 4, notes.internalChanges)
    }

    @Test
    fun untypedGeneratedEntry_isAnImprovement_andBotsAreNotCredited() {
        val notes =
            parseReleaseNotes(
                """
                ## What's Changed
                * Add manual mTLS certificates by @JerryFecl3 in https://github.com/o/r/pull/1
                * i18n: add Spanish localization by @Hy4ri in https://github.com/o/r/pull/2
                * chore: bump okhttp by @dependabot[bot] in https://github.com/o/r/pull/3
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                ReleaseNoteSection(
                    ReleaseNoteGroup.IMPROVEMENTS,
                    null,
                    listOf("Add manual mTLS certificates", "Add Spanish localization"),
                ),
            ),
            notes.sections,
        )
        assertEquals(listOf("JerryFecl3", "Hy4ri"), notes.contributors)
    }

    @Test
    fun handWrittenNotes_keepTheirHeadingsAndStripMarkdown() {
        val notes =
            parseReleaseNotes(
                """
                Big release.

                ### Highlights
                - **Groups** are now [shared rooms](https://example.com)
                - Faster `session.resume`
                - fix: crash on rotate (#12)
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                ReleaseNoteSection(null, null, listOf("Big release.")),
                ReleaseNoteSection(null, "Highlights", listOf("Groups are now shared rooms", "Faster session.resume")),
                ReleaseNoteSection(ReleaseNoteGroup.FIXES, null, listOf("Crash on rotate")),
            ),
            notes.sections,
        )
        assertEquals(emptyList<String>(), notes.contributors)
    }

    @Test
    fun duplicateEntries_collapse() {
        val notes =
            parseReleaseNotes(
                """
                * fix(ws): reconnect on resume by @a in https://github.com/o/r/pull/1
                * fix(chat): reconnect on resume by @b in https://github.com/o/r/pull/2
                """.trimIndent(),
            )

        assertEquals(listOf("Reconnect on resume"), notes.sections.single().items)
        assertEquals(listOf("a", "b"), notes.contributors)
    }

    @Test
    fun onlyChangelogLink_isEmpty() {
        val notes = parseReleaseNotes("**Full Changelog**: https://github.com/o/r/compare/v1...v2")

        assertTrue(notes.isEmpty)
    }

    @Test
    fun onlyInternalChanges_isEmpty() {
        val notes = parseReleaseNotes("* ci: cache gradle by @a in https://github.com/o/r/pull/1")

        assertTrue(notes.isEmpty)
        assertEquals(1, notes.internalChanges)
    }

    @Test
    fun fixWithInternalScope_isHidden() {
        val notes =
            parseReleaseNotes(
                """
                * fix(ci): label ci-red via gh api by @a in https://github.com/o/r/pull/1
                * fix(chat,test): keep draft on rotate by @a in https://github.com/o/r/pull/2
                * fix(chat): keep scroll position by @a in https://github.com/o/r/pull/3
                """.trimIndent(),
            )

        assertEquals(listOf("Keep scroll position"), notes.sections.single().items)
        assertEquals(2, notes.internalChanges)
    }

    private companion object {
        val V3_4_0 =
            """
            ## What's Changed
            * fix(chat): seal reasoning-only segment at tool.start by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1833
            * fix(chat): retire stale typing tail on idle reconciliation by @Erfan817 in https://github.com/Hy4ri/hermes-mobile/pull/1834
            * fix(chat): switch models via session.mutate and drop the /model bubble by @trigor74 in https://github.com/Hy4ri/hermes-mobile/pull/1840
            * fix(ws): send active profile on session-bound RPCs by @trigor74 in https://github.com/Hy4ri/hermes-mobile/pull/1839
            * chore: update gateway contract snapshot by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1843

            ## New Contributors
            * @trigor74 made their first contribution in https://github.com/Hy4ri/hermes-mobile/pull/1840

            **Full Changelog**: https://github.com/Hy4ri/hermes-mobile/compare/v3.3.0...v3.4.0
            """.trimIndent()

        val V3_3_0 =
            """
            ## What's Changed
            * chore: update gateway contract snapshot by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1822
            * fix(chat): show the clarify answer bubble on submit (#1824) by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1825
            * ci(release): build release APK with Debian OpenJDK to match F-Droid by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1826
            * feat(icon): recolor launcher icon to the Default theme by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1827
            * docs(readme): trim README and move details to the wiki by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1828
            * docs(fastlane): rewrite the F-Droid description and add a Ko-fi link by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1829
            * fix(chat): align reasoning card with tool bubbles by @Hy4ri in https://github.com/Hy4ri/hermes-mobile/pull/1830


            **Full Changelog**: https://github.com/Hy4ri/hermes-mobile/compare/v3.2.3...v3.3.0
            """.trimIndent()
    }
}
