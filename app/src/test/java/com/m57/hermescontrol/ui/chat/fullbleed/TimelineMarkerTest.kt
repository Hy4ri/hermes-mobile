package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineMarkerTest {
    @Test
    fun knownKindsMapToLabels() {
        assertEquals(R.string.timeline_marker_model_switch, timelineMarkerLabelRes("model_switch"))
        assertEquals(
            R.string.timeline_marker_personality_switch,
            timelineMarkerLabelRes("personality_switch"),
        )
        assertEquals(R.string.timeline_marker_auto_continue, timelineMarkerLabelRes("auto_continue"))
        assertEquals(
            R.string.timeline_marker_delegation_complete,
            timelineMarkerLabelRes("async_delegation_complete"),
        )
        assertEquals(
            R.string.timeline_marker_skill_invocation,
            timelineMarkerLabelRes("skill_invocation"),
        )
        assertEquals(
            R.string.timeline_marker_internal_notification,
            timelineMarkerLabelRes("internal_notification"),
        )
        assertEquals(
            R.string.timeline_marker_max_iterations,
            timelineMarkerLabelRes("max_iterations_reached"),
        )
    }

    @Test
    fun unknownAndNullKindsHaveNoLabel() {
        assertNull(timelineMarkerLabelRes("hidden"))
        assertNull(timelineMarkerLabelRes("some_future_kind"))
        assertNull(timelineMarkerLabelRes(null))
        assertNull(timelineMarkerLabelRes(""))
    }

    @Test
    fun modelParsedFromMarkerContent() {
        assertEquals(
            "gpt-5",
            markerModelFromContent(
                "[System: The active model for this chat has changed to gpt-5 via provider openai. ...]",
            ),
        )
        assertEquals(
            "deepseek-v4",
            markerModelFromContent(
                "The active model for this chat has changed to deepseek-v4. From this point forward...",
            ),
        )
        assertEquals(
            "gpt-4.5",
            markerModelFromContent(
                "The active model for this chat has changed to gpt-4.5 via provider openai.",
            ),
        )
    }

    @Test
    fun modelParseHandlesMissingOrOddContent() {
        assertNull(markerModelFromContent(""))
        assertNull(markerModelFromContent("[System: personality switched]"))
        assertNull(markerModelFromContent("changed to"))
    }
}
