package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.ApiClient
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Turn-boundary correlation contract for reply notifications.
 *
 * The invariant under test: a completion may only be bound to a REST row that
 * is provably inside its own turn — strictly above the pre-submit high-watermark
 * — and only when exactly one such row matches. Everything else fails closed,
 * because a notification that stays active is strictly better than one that is
 * dismissed by the wrong duplicate reply.
 *
 * There is deliberately no clock anywhere in this file: Android notification
 * time and Hermes server time come from different machines.
 */
class TurnCorrelationTrackerTest {
    private val writeLog = mutableListOf<List<TurnBoundary>>()
    private var storedBoundaries: List<TurnBoundary> = emptyList()

    @Before
    fun setUp() {
        writeLog.clear()
        storedBoundaries = emptyList()
        TurnCorrelationTracker.resetForTest()
        TurnCorrelationTracker.attachStore(recordingStore())
    }

    @After
    fun tearDown() {
        TurnCorrelationTracker.resetForTest()
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private fun recordingStore(): TurnBoundaryStore =
        object : TurnBoundaryStore {
            override fun read(): List<TurnBoundary> = storedBoundaries

            override fun write(boundaries: List<TurnBoundary>) {
                storedBoundaries = boundaries
                writeLog.add(boundaries)
            }
        }

    private fun row(
        id: Int,
        text: String,
        role: String = "assistant",
    ) = SessionMessage(id = id, role = role, content = JsonPrimitive(text))

    private fun boundary(
        beforeMessageId: Int,
        scopeId: String = "default",
        sessionId: String = "session",
    ) = TurnBoundary(
        scopeId = scopeId,
        sessionId = sessionId,
        beforeMessageId = beforeMessageId,
        generation = 1L,
        armedAt = 1L,
    )

    /** Resolver over a scripted sequence of REST pages; records how often it fetched. */
    private fun scriptedResolver(
        pages: List<List<SessionMessage>>,
        fetches: MutableList<Int> = mutableListOf(),
    ): TurnRowResolver {
        var index = 0
        return TurnRowResolver(
            fetchLatestRows = {
                fetches.add(index)
                pages.getOrNull(index++)
            },
            retryDelaysMs = listOf(0L, 0L, 0L),
        )
    }

    private fun resolve(
        pages: List<List<SessionMessage>>,
        completionText: String,
        beforeMessageId: Int = 0,
    ): Int? =
        runBlocking {
            scriptedResolver(pages).resolve("session", boundary(beforeMessageId), completionText)
        }

    // ── A/B: historical duplicates can never win ─────────────────────────────

    @Test
    fun historicalDuplicateBelowBoundaryIsExcluded() {
        val rows = listOf(row(5, "Done"), row(11, "Done"))
        assertEquals(11, resolve(listOf(rows), "Done", beforeMessageId = 10))
    }

    @Test
    fun oldDuplicateVisibleBeforeOwnRowAppearsIsNeverAccepted() {
        // Attempt 1 sees only the historical duplicate; the turn's own row has
        // not been persisted yet. Resolving on the first attempt would bind the
        // notification to the wrong reply.
        val fetches = mutableListOf<Int>()
        val pages =
            listOf(
                listOf(row(5, "Done")),
                listOf(row(5, "Done"), row(11, "Done")),
            )
        val resolved =
            runBlocking {
                scriptedResolver(pages, fetches)
                    .resolve("session", boundary(beforeMessageId = 10), "Done")
            }
        assertEquals(11, resolved)
        assertEquals(2, fetches.size)
    }

    @Test
    fun rowPersistedOnlyOnFinalAttemptStillResolves() {
        val pages =
            listOf(
                listOf(row(5, "Done")),
                listOf(row(5, "Done")),
                listOf(row(5, "Done"), row(11, "Done")),
            )
        assertEquals(11, resolve(pages, "Done", beforeMessageId = 10))
    }

    // ── C: ambiguity fails closed ────────────────────────────────────────────

    @Test
    fun twoMatchingRowsAboveBoundaryFailClosedWithoutRetrying() {
        val fetches = mutableListOf<Int>()
        val pages = listOf(listOf(row(11, "Done"), row(12, "Done")), listOf(row(11, "Done"), row(12, "Done")))
        val resolved =
            runBlocking {
                scriptedResolver(pages, fetches)
                    .resolve("session", boundary(beforeMessageId = 10), "Done")
            }
        assertNull("Ambiguous turn must not be guessed", resolved)
        assertEquals("Ambiguity is terminal — no pointless retries", 1, fetches.size)
    }

    @Test
    fun nonMatchingContentAboveBoundaryFailsClosed() {
        assertNull(resolve(listOf(listOf(row(11, "Other"))), "Done", beforeMessageId = 10))
    }

    @Test
    fun userRowIsNotACandidate() {
        assertNull(resolve(listOf(listOf(row(11, "Done", role = "user"))), "Done", beforeMessageId = 10))
    }

    @Test
    fun blankCompletionTextNeverResolves() {
        assertNull(resolve(listOf(listOf(row(11, "Done"))), "   ", beforeMessageId = 10))
    }

    // ── Truncation / MEDIA ───────────────────────────────────────────────────

    @Test
    fun identicalHundredCharacterPrefixStillDisambiguates() {
        val prefix = "x".repeat(100)
        val rows =
            listOf(
                row(11, prefix + "A"),
                row(12, prefix + "B"),
            )
        // The full completion text is compared, never the 100-char preview, so
        // the shared prefix cannot make the two rows interchangeable.
        assertEquals(12, resolve(listOf(rows), prefix + "B", beforeMessageId = 10))
    }

    @Test
    fun mediaReplyResolvesByCanonicalTextAndPath() {
        val completion = "Here is the image MEDIA:/opt/hermes/image.png"
        val rows =
            listOf(
                row(11, "Here is another image MEDIA:/opt/hermes/other.png"),
                row(12, "Here is the image MEDIA:/opt/hermes/image.png"),
            )
        assertEquals(12, resolve(listOf(rows), completion, beforeMessageId = 10))
    }

    @Test
    fun mediaOnlyReplyResolvesByPathInsteadOfMatchingAnyAssistantRow() {
        val rows =
            listOf(
                row(11, "something else entirely"),
                row(12, "MEDIA:/opt/hermes/image.png"),
            )
        assertEquals(12, resolve(listOf(rows), "MEDIA:/opt/hermes/image.png", beforeMessageId = 10))
    }

    @Test
    fun duplicateMediaRowsAfterBoundaryFailClosed() {
        val rows =
            listOf(
                row(11, "MEDIA:/opt/hermes/image.png"),
                row(12, "MEDIA:/opt/hermes/image.png"),
            )
        assertNull(resolve(listOf(rows), "MEDIA:/opt/hermes/image.png", beforeMessageId = 10))
    }

    // ── F: REST failure ──────────────────────────────────────────────────────

    @Test
    fun restFailureFailsClosed() {
        val resolver =
            TurnRowResolver(
                fetchLatestRows = { throw IOException("gateway unreachable") },
                retryDelaysMs = listOf(0L, 0L, 0L),
            )
        assertNull(runBlocking { resolver.resolve("session", boundary(10), "Done") })
    }

    @Test
    fun emptyPagesFailClosed() {
        assertNull(resolve(listOf(emptyList(), emptyList()), "Done", beforeMessageId = 10))
    }

    // ── G/H/I: missing, cross-profile and cross-session boundaries ───────────

    @Test
    fun noBoundaryMeansNoLookupAtAll() {
        val fetches = mutableListOf<Int>()
        val resolved =
            runBlocking {
                correlateCompletedTurnRow(
                    scopeId = "default",
                    sessionId = "session",
                    completionText = "Done",
                    resolver = scriptedResolver(listOf(listOf(row(11, "Done"))), fetches),
                )
            }
        assertNull(resolved)
        assertTrue("Uncorrelatable turns must not even hit REST", fetches.isEmpty())
    }

    @Test
    fun boundaryFromAnotherProfileIsInvisible() {
        TurnCorrelationTracker.armBoundary("other-profile", "session", 10)
        val fetches = mutableListOf<Int>()
        val resolved =
            runBlocking {
                correlateCompletedTurnRow(
                    scopeId = "default",
                    sessionId = "session",
                    completionText = "Done",
                    resolver = scriptedResolver(listOf(listOf(row(11, "Done"))), fetches),
                )
            }
        assertNull(resolved)
        assertTrue(fetches.isEmpty())
    }

    @Test
    fun boundaryFromAnotherSessionIsInvisible() {
        TurnCorrelationTracker.armBoundary("default", "other-session", 10)
        val fetches = mutableListOf<Int>()
        val resolved =
            runBlocking {
                correlateCompletedTurnRow(
                    scopeId = "default",
                    sessionId = "session",
                    completionText = "Done",
                    resolver = scriptedResolver(listOf(listOf(row(11, "Done"))), fetches),
                )
            }
        assertNull(resolved)
        assertTrue(fetches.isEmpty())
    }

    // ── Boundary lifecycle ───────────────────────────────────────────────────

    @Test
    fun correlateResolvesThenConsumesTheBoundary() {
        TurnCorrelationTracker.armBoundary("default", "session", 10)
        val pages = listOf(listOf(row(11, "Done")))
        val first =
            runBlocking {
                correlateCompletedTurnRow("default", "session", "Done", scriptedResolver(pages))
            }
        assertEquals(11, first)
        assertNull(
            "The turn is over — its boundary must be spent",
            TurnCorrelationTracker.boundaryFor("default", "session"),
        )

        val second =
            runBlocking {
                correlateCompletedTurnRow("default", "session", "Done", scriptedResolver(pages))
            }
        assertNull("A second completion must not reuse the consumed boundary", second)
    }

    @Test
    fun armingPersistsAndSurvivesProcessRestart() {
        TurnCorrelationTracker.armBoundary("default", "session", 41)
        assertEquals(41, storedBoundaries.single().beforeMessageId)

        // Simulate a fresh process: same store content, empty in-memory cache.
        TurnCorrelationTracker.resetForTest()
        TurnCorrelationTracker.attachStore(recordingStore())
        assertEquals(41, TurnCorrelationTracker.boundaryFor("default", "session")?.beforeMessageId)
    }

    /**
     * The generation counter is in-memory only, so a restart must lift it above
     * whatever the restored boundaries already used. Otherwise an old
     * completion's clear (generation N) matches the NEW boundary (also N) and the
     * stale-clear guard stops protecting the newer turn.
     */
    @Test
    fun restoredBoundaryGenerationAdvancesTheCounterPastIt() {
        storedBoundaries =
            listOf(
                TurnBoundary(
                    scopeId = "default",
                    sessionId = "session",
                    beforeMessageId = 10,
                    generation = 37L,
                    armedAt = System.currentTimeMillis(),
                ),
            )
        TurnCorrelationTracker.resetForTest()
        TurnCorrelationTracker.attachStore(recordingStore())
        assertEquals(37L, TurnCorrelationTracker.boundaryFor("default", "session")?.generation)

        val next = TurnCorrelationTracker.armBoundary("default", "session", 25)
        assertNotNull(next)
        assertTrue(
            "A post-restart boundary must not reuse a recovered generation (got ${next?.generation})",
            (next?.generation ?: 0L) > 37L,
        )

        // The old completion, still holding generation 37, must not clear it.
        assertFalse(TurnCorrelationTracker.clearBoundary("default", "session", 37L))
        assertEquals(25, TurnCorrelationTracker.boundaryFor("default", "session")?.beforeMessageId)
    }

    @Test
    fun reArmingForTheSameSessionReplacesTheBoundary() {
        TurnCorrelationTracker.armBoundary("default", "session", 10)
        TurnCorrelationTracker.armBoundary("default", "session", 25)
        assertEquals(25, TurnCorrelationTracker.boundaryFor("default", "session")?.beforeMessageId)
        assertEquals(1, storedBoundaries.size)
    }

    @Test
    fun staleGenerationCannotClearANewerBoundary() {
        val stale = TurnCorrelationTracker.armBoundary("default", "session", 10)
        TurnCorrelationTracker.armBoundary("default", "session", 25)
        assertNotNull(stale)
        assertFalse(TurnCorrelationTracker.clearBoundary("default", "session", stale?.generation))
        assertEquals(25, TurnCorrelationTracker.boundaryFor("default", "session")?.beforeMessageId)
    }

    @Test
    fun blankIdentityOrNegativeBoundaryNeverArms() {
        assertNull(TurnCorrelationTracker.armBoundary("", "session", 10))
        assertNull(TurnCorrelationTracker.armBoundary("default", "  ", 10))
        assertNull(TurnCorrelationTracker.armBoundary("default", "session", -1))
        assertTrue(writeLog.isEmpty())
    }

    @Test
    fun zeroBoundaryIsValidForAnEmptyTranscript() {
        // A brand-new session has no rows yet: the next turn's rows are all
        // above 0, and there are no historical duplicates to exclude.
        assertNotNull(TurnCorrelationTracker.armBoundary("default", "session", 0))
        assertEquals(0, TurnCorrelationTracker.boundaryFor("default", "session")?.beforeMessageId)
    }

    /**
     * The probe is best-effort by contract and must never fail the caller's turn.
     *
     * A gateway-layer `Error` (uninitialised client, NoClassDefFound) is not an
     * `Exception`, so it used to escape the caller's `catch (Exception)`, abort
     * the notification-reply send, and leak out of a background scope — which CI
     * then reported as `UncaughtExceptionsBeforeTest` in an unrelated later test
     * class. Encode that as a regression so it cannot come back.
     */
    @Test
    fun boundaryProbeSwallowsEvenGatewayLayerErrors() {
        mockkObject(ApiClient)
        try {
            every { ApiClient.hermesApi } throws ExceptionInInitializerError("ApiClient unavailable")
            assertFalse(
                "A broken gateway layer must not fail the turn",
                runBlocking { captureTurnBoundary("default", "session") },
            )
            assertNull(TurnCorrelationTracker.boundaryFor("default", "session"))
        } finally {
            unmockkObject(ApiClient)
        }
    }
}
