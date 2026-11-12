package com.ramiromaceda.intercom

import org.junit.Assert.*
import org.junit.Test

class PlayableLockedBufferTest {
    private val pcm = shortArrayOf(1, 2, 3, 4)

    @Test fun partialCaptureIsPublishedWhenUnlockingWithoutAnyNewRead() {
        val buffer = PlayableLockedBuffer(16)
        buffer.observeEligibility(true)
        buffer.append(pcm, 4, 100, 101)
        val sample = buffer.observeEligibility(false)!!
        assertArrayEquals(pcm, sample.pcm)
        assertEquals(100L, sample.firstReadMs)
        assertEquals(101L, sample.lastReadMs)
        assertEquals(0, buffer.frames)
    }

    @Test fun manualStopPublishesEligiblePcmWithoutAnyTimestampOrMinute() {
        val buffer = PlayableLockedBuffer(16000 * 60)
        buffer.observeEligibility(true)
        buffer.append(pcm, 4, 100, 101)
        buffer.note("Timestamp ausente; validación estricta no aprobada")
        val sample = buffer.finish("Stop manual")!!
        assertEquals(4, sample.pcm.size)
        assertTrue(sample.notes.contains("Timestamp ausente"))
        assertNull(buffer.finish("Segundo Stop"))
    }

    @Test fun memoryIsCappedAndSnapshotNeverSharesWritableStorage() {
        val buffer = PlayableLockedBuffer(6)
        buffer.observeEligibility(true)
        assertNull(buffer.append(pcm, 4, 100, 101))
        val capped = buffer.append(pcm, 4, 102, 103)!!
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 1, 2), capped.pcm)
        assertNull(buffer.append(pcm, 4, 104, 105))
        buffer.observeEligibility(false)
        buffer.observeEligibility(true)
        buffer.append(shortArrayOf(9, 9), 2, 106, 107)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 1, 2), capped.pcm)
    }

    @Test fun ineligibleInputIsNotRetainedAndIntervalsAreNotConcatenated() {
        val buffer = PlayableLockedBuffer(16)
        assertNull(buffer.append(pcm, 4, 0, 1))
        assertNull(buffer.finish("Sin ruta/bloqueo"))
        buffer.observeEligibility(true)
        buffer.append(pcm, 4, 2, 3)
        val first = buffer.observeEligibility(false)!!
        buffer.observeEligibility(true)
        buffer.append(shortArrayOf(5, 6), 2, 4, 5)
        val second = buffer.finish("Stop")!!
        assertEquals(4, first.pcm.size)
        assertArrayEquals(shortArrayOf(5, 6), second.pcm)
    }

    @Test fun listeningSurvivesStrictTimestampResetAndEmptyLaterAttempt() {
        val buffer = PlayableLockedBuffer(16)
        val strict = LockedVoiceWindow()
        buffer.observeEligibility(true)
        strict.observeScreen(true, 1_000_000_000L)
        buffer.append(pcm, 4, 1, 2)
        strict.append(pcm, 4, null, 2_000_000_000L, "Timestamp ausente: código -3")
        val saved = buffer.finish("Stop")!!
        assertEquals(4, saved.pcm.size)
        assertFalse(strict.completed)
        assertEquals("Timestamp ausente: código -3", strict.result().recentResets.last().reason)
        assertNull(buffer.finish("Fallo posterior sin PCM"))
        assertArrayEquals(pcm, saved.pcm)
    }
}
