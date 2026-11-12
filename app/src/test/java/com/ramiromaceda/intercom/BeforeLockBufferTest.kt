package com.ramiromaceda.intercom

import org.junit.Assert.*
import org.junit.Test

class BeforeLockBufferTest {
    @Test fun keepsLastPreLockFramesInOrderAndNeverAcceptsPostLockFrames() {
        val buffer = BeforeLockBuffer(8)
        buffer.append(shortArrayOf(1, 2, 3, 4), 4, 100, 101)
        buffer.append(shortArrayOf(5, 6, 7, 8), 4, 102, 103)
        buffer.append(shortArrayOf(9, 10, 11, 12), 4, 104, 105)
        val clip = buffer.finish()!!
        assertArrayEquals(shortArrayOf(5, 6, 7, 8, 9, 10, 11, 12), clip.pcm)
        assertEquals(102L, clip.firstReadMs)
        buffer.append(shortArrayOf(99), 1, 106, 107)
        assertNull(buffer.finish())
        assertEquals(12.toShort(), clip.pcm.last())
    }

    @Test fun noReadsMeansNoBeforeClip() { assertNull(BeforeLockBuffer().finish()) }
}
