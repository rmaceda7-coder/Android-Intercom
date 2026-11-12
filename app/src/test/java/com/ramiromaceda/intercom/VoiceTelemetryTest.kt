package com.ramiromaceda.intercom

import org.junit.Assert.*
import org.junit.Test

class VoiceTelemetryTest {
    private val start = 1_000_000_000L
    private fun observe(log: VoiceTelemetry, now: Long, result: Int, pcm: ShortArray, silenced: Boolean?) {
        log.read(now, "SCO input id=2", "SCO comm id=1", "SCO output id=1", result, pcm,
            silenced, 3, false, 1000)
    }

    @Test fun rmsPeakAndReadErrorsArePerWindowNotSessionCumulative() {
        val log = VoiceTelemetry("session-test", start)
        log.screen(true, false, start)
        observe(log, start + 500_000_000, 2, shortArrayOf(3, 4), false)
        observe(log, start + 1_000_000_000, 0, shortArrayOf(), false)
        log.screen(false, true, start + 1_100_000_000)
        observe(log, start + 2_000_000_000, 2, shortArrayOf(0, 0), true)
        observe(log, start + 3_000_000_000, -3, shortArrayOf(30000), null)
        val rows = log.text().lines().filter { it.startsWith("SEGUNDO") }
        assertEquals(3, rows.size)
        assertTrue(rows[0].contains("RMS_PCM=3.54 picoPCM=4"))
        assertTrue(rows[0].contains("readsZero=1"))
        assertTrue(rows[1].contains("RMS_PCM=0.00 picoPCM=0"))
        assertTrue(rows[1].contains("isClientSilenced=true"))
        assertTrue(rows[2].contains("readErrors={-3=1}"))
        assertTrue(rows[2].contains("RMS_PCM=N/A(sin frames)"))
        assertTrue(rows[2].contains("desconocido(configuración ausente)"))
        assertTrue(log.text().contains("PANTALLA interactive=false keyguardLocked=true"))
        assertTrue(log.text().contains("session=session-test"))
    }

    @Test fun longSchedulingGapIsReportedAsRealWindowNotInventedSeconds() {
        val log = VoiceTelemetry("gap", start)
        observe(log, start + 10_000_000_000, 0, shortArrayOf(), null)
        val rows = log.text().lines().filter { it.startsWith("SEGUNDO") }
        assertEquals(1, rows.size)
        assertTrue(rows.single().contains("dtMs=10000"))
    }

    @Test fun traceIsBoundedAndReportsDroppedEntries() {
        val log = VoiceTelemetry("bounded", start)
        repeat(600) { log.event("event-$it", start + it) }
        assertTrue(log.text().contains("descartadas=88"))
        assertFalse(log.text().contains(": event-0\n"))
        assertTrue(log.text().contains("event-599"))
    }
}
