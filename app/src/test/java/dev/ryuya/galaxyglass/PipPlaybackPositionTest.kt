package dev.ryuya.galaxyglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipPlaybackPositionTest {
    @Test
    fun projectsForwardAndReversePlaybackFromTheSessionTimestamp() {
        val state = PipPlaybackPosition(3_000, 10_000, 1.5f, true)
        assertEquals(6_000L, state.positionAt(15_000, 12_000))
        assertEquals(1_000L, state.copy(speed = -1f).positionAt(15_000, 12_000))
    }

    @Test
    fun leavesPausedOrUntimedPlaybackAtTheReportedPosition() {
        val state = PipPlaybackPosition(3_000, 10_000, 1f, false)
        assertEquals(3_000L, state.positionAt(15_000, 12_000))
        assertEquals(3_000L, state.copy(moving = true, updatedAtMs = 0).positionAt(15_000, 12_000))
    }

    @Test
    fun clampsThePositionAndHidesUnknownTimelines() {
        val state = PipPlaybackPosition(3_000, 10_000, 1f, true)
        assertEquals(4_000L, state.positionAt(4_000, 12_000))
        assertEquals(0L, state.copy(speed = -2f).positionAt(4_000, 12_000))
        assertNull(state.positionAt(0, 12_000))
        assertNull(state.copy(positionMs = -1).positionAt(4_000, 12_000))
    }
}
