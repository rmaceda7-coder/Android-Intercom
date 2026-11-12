package com.ramiromaceda.intercom

import org.junit.Assert.*
import org.junit.Test

class LockedVoiceWindowTest {
    private val chunk = ShortArray(320) { 42 }
    private val epoch = 1_000_000_000L
    private val first = epoch + 1_000_000_000L
    private val chunkNs = 20_000_000L

    @Test fun sixtySecondsCompleteOnlyAfterAllFramesInOneLock() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        var result: ShortArray? = null
        repeat(3000) { index ->
            val time = first + index * chunkNs
            result = window.append(chunk, chunk.size, time, time + chunkNs)
            if (index < 2999) { assertFalse(window.completed); assertNull(result) }
        }
        assertTrue(window.completed)
        assertEquals(960000, result!!.size)
        assertEquals(42.toShort(), result!![959999])
        assertEquals(60_000_000_000L, window.sourceToNs - window.sourceFromNs)
        // Once complete, later screen transitions cannot mutate the retained PCM.
        window.observeScreen(false, first + 60_000_000_000L)
        assertEquals(960000, window.frames)
        assertNull(window.append(chunk, 320, first, first + chunkNs))
    }

    @Test fun unlockingWithoutAnyReadStillDiscardsPartialWindow() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        window.append(chunk, 320, first, first + chunkNs)
        assertEquals(320, window.frames)
        window.observeScreen(false, first + chunkNs)
        assertEquals(0, window.frames)
        window.observeScreen(true, first + 2 * chunkNs)
        window.append(chunk, 320, first + 2 * chunkNs, first + 3 * chunkNs)
        assertEquals(0, window.frames) // New lock must pass its own pre-lock PCM guard.
    }

    @Test fun missingTimestampDiscardsPartialCapture() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        window.append(chunk, 320, first, first + chunkNs)
        window.append(chunk, 320, null, first + 2 * chunkNs)
        assertEquals(0, window.frames)
        assertTrue(window.invalidation.contains("Timestamp"))
    }

    @Test fun discontinuityCannotBeConcatenatedIntoSixtySeconds() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        window.append(chunk, 320, first, first + chunkNs)
        window.append(chunk, 320, first + 100_000_000L, first + 120_000_000L)
        assertEquals(0, window.frames)
        assertTrue(window.invalidation.contains("Discontinuidad"))
    }

    @Test fun outputUnderrunInvalidatesIncompleteEvidence() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        window.append(chunk, 320, first, first + chunkNs)
        window.invalidate("Salida: underrun")
        assertEquals(0, window.frames)
        assertFalse(window.completed)
    }

    @Test fun futureFramesAndPreLockFramesAreNotAccepted() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        assertNull(window.append(chunk, 320, epoch, epoch + chunkNs))
        assertEquals(0, window.frames)
        assertNull(window.append(chunk, 320, first, first - 1))
        assertEquals(0, window.frames)
    }

    @Test fun exactOneBlockGapAndOverlapAreRejected() {
        for (jump in listOf(0L, 40_000_000L)) {
            val window = LockedVoiceWindow()
            window.observeScreen(true, epoch)
            window.append(chunk, 320, first, first + chunkNs)
            window.append(chunk, 320, first + jump, first + jump + chunkNs)
            assertEquals(0, window.frames)
            assertFalse(window.completed)
        }
    }

    @Test fun recoveringFromTimestampLossNeedsEntireNewMinute() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        window.append(chunk, 320, first, first + chunkNs)
        window.append(chunk, 320, null, first + 2 * chunkNs)
        repeat(2999) { index ->
            val time = first + (index + 2) * chunkNs
            assertNull(window.append(chunk, 320, time, time + chunkNs))
        }
        assertFalse(window.completed)
        val time = first + 3001 * chunkNs
        assertNotNull(window.append(chunk, 320, time, time + chunkNs))
        assertTrue(window.completed)
    }

    @Test fun resetReasonAndPreviousProgressSurviveAcceptedPcmInSameIteration() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        window.append(chunk, 320, first, first + chunkNs)
        window.invalidate("Salida: underrun exacto", first + chunkNs)
        window.append(chunk, 320, first + chunkNs, first + 2 * chunkNs)
        val result = window.result()
        assertEquals(320, result.currentFrames)
        assertEquals(320, result.lastProgressReset!!.framesBeforeReset)
        assertEquals("Salida: underrun exacto", result.lastProgressReset!!.reason)
        assertEquals("Salida: underrun exacto", result.recentResets.last().reason)
    }

    @Test fun unlockKeepsExactValidatedDurationAndBestAttempt() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        repeat(10) { index ->
            val time = first + index * chunkNs
            window.append(chunk, 320, time, time + chunkNs)
        }
        window.observeScreen(false, first + 10 * chunkNs)
        val result = window.result()
        assertEquals(0, result.currentFrames)
        assertEquals(3200, result.framesAtUnlock)
        assertEquals(3200, result.bestFramesAtUnlock)
        assertEquals(3200, result.bestFrames)
        assertTrue(result.recentResets.last().reason.contains("pantalla"))
    }

    @Test fun timestampLossReasonDoesNotDisappearAmongZeroProgressErrors() {
        val window = LockedVoiceWindow()
        window.observeScreen(true, epoch)
        window.append(chunk, 320, first, first + chunkNs)
        window.append(chunk, 320, null, first + 2 * chunkNs, "Timestamp ausente: código -3")
        repeat(30) { window.invalidate("Otro fallo $it", first + 3 * chunkNs) }
        val result = window.result()
        assertEquals(8, result.recentResets.size)
        assertEquals("Timestamp ausente: código -3", result.lastProgressReset!!.reason)
        assertEquals(320, result.lastProgressReset!!.framesBeforeReset)
    }
}
