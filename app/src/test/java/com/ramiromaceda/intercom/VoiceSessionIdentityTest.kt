package com.ramiromaceda.intercom

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VoiceSessionIdentityTest {
    @Before fun reset() {
        DiagnosticState.currentVoice = null
        DiagnosticState.previousVoice = null
        DiagnosticState.sample = null
    }

    private fun clip(session: String, id: String, locked: Boolean) = CaptureSample(shortArrayOf(1, 2), locked,
        100, 101, sessionId = session, sampleId = id, segment = if (locked) "BLOQUEADO" else "ANTES")

    @Test fun newEmptySessionDoesNotMasqueradeAsSourceOfOldSamples() {
        DiagnosticState.beginVoice("A")
        DiagnosticState.publishVoice("A", false, clip("A", "before-A", false))
        DiagnosticState.publishVoice("A", true, clip("A", "locked-A", true))
        DiagnosticState.endVoice("A")
        DiagnosticState.beginVoice("B")
        assertNull(DiagnosticState.currentVoice!!.before)
        assertNull(DiagnosticState.currentVoice!!.blocked)
        assertEquals("A", DiagnosticState.previousVoice!!.sessionId)
        assertEquals("before-A", DiagnosticState.findSample("before-A")!!.sampleId)
        assertEquals("A", DiagnosticState.sample!!.sessionId)
        assertNull(DiagnosticState.findSample("missing-id"))
        DiagnosticState.beginVoice("C")
        assertEquals("A", DiagnosticState.previousVoice!!.sessionId)
    }

    @Test fun beforeAndBlockedSamplesOfSameSessionRemainIndependentlyAddressable() {
        DiagnosticState.beginVoice("A")
        DiagnosticState.publishVoice("A", false, clip("A", "before", false))
        DiagnosticState.publishVoice("A", true, clip("A", "locked", true))
        assertFalse(DiagnosticState.findSample("before")!!.locked)
        assertTrue(DiagnosticState.findSample("locked")!!.locked)
        assertEquals("A", DiagnosticState.findSample("before")!!.sessionId)
        DiagnosticState.publishVoice("wrong-session", true, clip("wrong-session", "wrong", true))
        assertNull(DiagnosticState.findSample("wrong"))
    }
}
