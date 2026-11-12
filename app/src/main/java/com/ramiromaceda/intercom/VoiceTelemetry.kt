package com.ramiromaceda.intercom

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** All diagnostic timestamps use MONOTONIC (System.nanoTime), including screen observations. */
object DiagnosticClock {
    fun nowNs() = System.nanoTime()
    fun nowMs() = nowNs() / 1_000_000L
}

/** Bounded, in-memory ~1 s windows. Long worker gaps are reported, never fabricated as empty seconds. */
class VoiceTelemetry(val sessionId: String, private val startedNs: Long) {
    private val entries = ArrayDeque<String>()
    private var dropped = 0
    private var windowNs = startedNs
    private var sequence = 0
    private var frames = 0L
    private var totalFrames = 0L
    private var positive = 0
    private var zeroReads = 0
    private val errors = linkedMapOf<Int, Int>()
    private var squareSum = 0.0
    private var peak = 0
    private var zeroSamples = 0L
    private val silenceStates = linkedSetOf<String>()
    private val inputRoutes = linkedSetOf<String>()
    private val phases = linkedSetOf<String>()
    private var state = "sin observación de pantalla"
    private var phase = "ANTES"
    private var seenLock = false
    private var lastSilenced: String? = null
    private var lastInput: String? = null
    private var latestOutput = "sin medición"
    private var latestCommunication = "sin medición"
    private var latestRecordState = 0
    private var latestMicMute = "desconocido"

    private fun add(line: String) {
        if (entries.size == 512) { entries.removeFirst(); dropped++ }
        entries.addLast(line)
    }

    @Synchronized fun event(message: String, nowNs: Long = DiagnosticClock.nowNs()) {
        add("EVENTO session=$sessionId tMonoNs=$nowNs: $message")
    }

    @Synchronized fun screen(interactive: Boolean, locked: Boolean, nowNs: Long) {
        val observed = "interactive=$interactive keyguardLocked=$locked"
        val offLocked = !interactive && locked
        if (offLocked) seenLock = true
        phase = when { offLocked -> "APAGADA_BLOQUEADA"; seenLock -> "DESPUES"; else -> "ANTES" }
        phases.add(phase)
        if (observed != state) {
            event("PANTALLA $observed fase=$phase (transición observada, no instante exacto del sistema)", nowNs)
            state = observed
        }
    }

    @Synchronized fun read(nowNs: Long, input: String, communication: String, output: String,
                           result: Int, pcm: ShortArray, silenced: Boolean?, recordingState: Int,
                           micMuted: Boolean, readDurationNs: Long) {
        val silent = silenced?.toString() ?: "desconocido(configuración ausente)"
        if (silent != lastSilenced) { event("isClientSilenced=$silent", nowNs); lastSilenced = silent }
        if (input != lastInput) { event("RUTA_ENTRADA=$input", nowNs); lastInput = input }
        silenceStates.add(silent)
        inputRoutes.add(input)
        phases.add(phase)
        latestOutput = output
        latestCommunication = communication
        latestRecordState = recordingState
        latestMicMute = micMuted.toString()
        when {
            result < 0 -> {
                errors[result] = (errors[result] ?: 0) + 1
                if (errors[result] == 1) event("AudioRecord.read error=$result (ver conteo por segundo)", nowNs)
            }
            result == 0 -> zeroReads++
            else -> {
                positive++
                val count = minOf(result, pcm.size)
                frames += count; totalFrames += count
                for (i in 0 until count) {
                    val value = pcm[i].toInt()
                    squareSum += value.toDouble() * value
                    peak = maxOf(peak, abs(value))
                    if (value == 0) zeroSamples++
                }
            }
        }
        if (readDurationNs > 100_000_000L) event("read lento: $readDurationNs ns", nowNs)
        if (nowNs - windowNs >= 1_000_000_000L) flush(nowNs)
    }

    @Synchronized fun flush(nowNs: Long) {
        if (nowNs <= windowNs) return
        val rms = if (frames == 0L) "N/A(sin frames)" else String.format(Locale.US, "%.2f", sqrt(squareSum / frames))
        add("SEGUNDO session=$sessionId seq=${++sequence} monoNs=$windowNs..$nowNs dtMs=${(nowNs - windowNs) / 1_000_000} " +
            "fase=${phases.joinToString("|")} frames=$frames totalFrames=$totalFrames readsPos=$positive readsZero=$zeroReads " +
            "readErrors=$errors RMS_PCM=$rms picoPCM=${if (frames == 0L) "N/A" else peak} ceros=$zeroSamples/$frames " +
            "isClientSilenced=${silenceStates.joinToString("|")} recordingState=$latestRecordState micMuteGlobal=$latestMicMute " +
            "entrada=${inputRoutes.joinToString("|")} comunicacion=$latestCommunication salidaControl=$latestOutput")
        windowNs = nowNs; frames = 0; positive = 0; zeroReads = 0; squareSum = 0.0; peak = 0; zeroSamples = 0
        errors.clear(); silenceStates.clear(); inputRoutes.clear(); phases.clear()
    }

    @Synchronized fun text(): String = "TELEMETRÍA Solo voz session=$sessionId inicioMonoNs=$startedNs; " +
        "reloj=MONOTONIC/System.nanoTime/AudioTimestamp.TIMEBASE_MONOTONIC; filas ~1 s (dt real); descartadas=$dropped\n" +
        entries.joinToString("\n")
}
