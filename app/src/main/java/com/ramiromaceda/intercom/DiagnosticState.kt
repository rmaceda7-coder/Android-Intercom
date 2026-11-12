package com.ramiromaceda.intercom

import android.media.AudioDeviceInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

data class RouteOption(val id: Int, val label: String, val headset: Boolean)
data class CaptureSample(val pcm: ShortArray, val locked: Boolean, val fromMs: Long, val toMs: Long,
                         val sourceFromNs: Long = 0, val sourceToNs: Long = 0, val note: String = "",
                         val sessionId: String = "sin-ID-legacy", val sampleId: String = UUID.randomUUID().toString(),
                         val segment: String = if (locked) "LEÍDO BLOQUEADO" else "OTRO") {
    fun label() = "$segment — sesión $sessionId — ${"%.2f".format(pcm.size / 16000.0)} s — muestra $sampleId"
}

data class VoiceSessionSamples(val sessionId: String, val telemetry: VoiceTelemetry,
                               val before: CaptureSample? = null, val blocked: CaptureSample? = null,
                               val active: Boolean = true)

/** In-process, bounded evidence. No file or microphone audio is uploaded or persisted. */
object DiagnosticState {
    val busy = AtomicBoolean(false)
    @Volatile var devices: List<RouteOption> = emptyList()
    @Volatile var status = "Selecciona archivo y casco. La sesión solo se inicia mediante el botón."
    @Volatile var input = "sin medición"
    @Volatile var output = "sin medición"
    @Volatile var progress = ""
    @Volatile var lockProgress = "Sin intervalo de bloqueo acreditado."
    @Volatile var mode = "Sin sesión"
    @Volatile var failure = "ninguno"
    @Volatile var sample: CaptureSample? = null
    @Volatile var noSampleReason = "Todavía no se ha guardado PCM elegible para escucha."
    @Volatile var strictValidation: VoiceValidationResult? = null
    @Volatile var captureReleaseUnconfirmed = false
    @Volatile var operationId = "sin operación"
    @Volatile var currentVoice: VoiceSessionSamples? = null
    @Volatile var previousVoice: VoiceSessionSamples? = null
    private val events = ArrayDeque<String>()
    @Synchronized fun event(message: String) {
        if (events.size >= 14) events.removeFirst()
        events.addLast("tMonoNs=${DiagnosticClock.nowNs()} operación=$operationId: $message")
    }

    @Synchronized fun beginVoice(id: String): VoiceSessionSamples {
        currentVoice?.takeIf { it.before != null || it.blocked != null }?.let { previousVoice = it.copy(active = false) }
        strictValidation = null
        lockProgress = "Sesión $id: todavía sin intervalo estricto observado."
        return VoiceSessionSamples(id, VoiceTelemetry(id, DiagnosticClock.nowNs())).also { currentVoice = it }
    }

    @Synchronized fun publishVoice(id: String, blocked: Boolean, clip: CaptureSample) {
        val current = currentVoice?.takeIf { it.sessionId == id } ?: return
        currentVoice = if (blocked) current.copy(blocked = clip) else current.copy(before = clip)
        sample = clip
    }

    @Synchronized fun endVoice(id: String) {
        currentVoice?.takeIf { it.sessionId == id }?.let { currentVoice = it.copy(active = false) }
    }

    @Synchronized fun findSample(id: String?): CaptureSample? = listOfNotNull(sample,
        currentVoice?.before, currentVoice?.blocked, previousVoice?.before, previousVoice?.blocked)
        .firstOrNull { it.sampleId == id }

    @Synchronized fun fullReport(): String = buildString {
        appendLine("INTERCOM — INFORME LOCAL DE ENTRADA HFP/LE. PCM no incluido. Tiempos MONOTONIC.")
        appendLine(summary())
        currentVoice?.let { appendLine("=== SESIÓN ACTUAL ==="); appendLine(it.telemetry.text()) }
        previousVoice?.let { appendLine("=== SESIÓN ANTERIOR (NO CONFUNDIR CON ACTUAL) ==="); appendLine(it.telemetry.text()) }
    }
    @Synchronized fun summary(): String = buildString {
        appendLine("Modo: $mode")
        appendLine("ID de operación: $operationId")
        currentVoice?.let {
            appendLine("Sesión Solo voz ACTUAL: ${it.sessionId}; ${if (it.active) "en curso" else "detenida"}.")
            if (it.before == null && it.blocked == null) appendLine("La sesión actual NO generó ninguna muestra nueva para escucha.")
            appendLine("Tramo anterior: ${it.before?.label() ?: "sin PCM anterior al bloqueo en esta sesión"}")
            appendLine("Tramo bloqueado: ${it.blocked?.label() ?: "esta sesión NO generó muestra de lecturas apagada/bloqueada"}")
        }
        previousVoice?.let {
            appendLine("Muestras ANTERIORES conservadas, sesión ${it.sessionId} (no son de la sesión actual):")
            appendLine(it.before?.label() ?: "Sin tramo anterior")
            appendLine(it.blocked?.label() ?: "Sin tramo bloqueado")
        }
        appendLine(status)
        appendLine("Fallo de flujo: $failure")
        if (captureReleaseUnconfirmed) appendLine("Entrada: cierre del micrófono no confirmado. Inicio y escucha bloqueados por seguridad; detén la app desde Ajustes si persiste.")
        appendLine("Entrada efectiva: $input")
        appendLine("Salida efectiva: $output")
        appendLine(progress)
        appendLine(lockProgress)
        strictValidation?.let { appendLine("Resultado independiente de la última prueba Solo voz:"); appendLine(it.describe()) }
        appendLine(sample?.let {
            "Última muestra conservada (el botón identifica su origen): ${it.label()}; ${if (it.locked) "PCM leído durante pantalla apagada y bloqueo observado, con entrada del casco verificada" else "sin bloqueo confirmado"}; lecturas t=${it.fromMs}–${it.toMs} ms. " +
                (if (it.sourceFromNs > 0) "AudioTimestamp MONOTONIC: ${it.sourceFromNs}–${it.sourceToNs} ns. " else "Instante de captura de origen no acreditado por timestamp. ") + it.note
        } ?: "Sin muestra para escucha: $noSampleReason")
        appendLine("Las direcciones ausentes solo permiten correlacionar el perfil, no la identidad física del casco.")
        events.forEach { appendLine(it) }
    }
}

fun isBluetoothHeadset(device: AudioDeviceInfo) = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

fun routeLabel(device: AudioDeviceInfo?): String = if (device == null) "no informada" else
    "${device.productName} | tipo=${device.type} ${when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "HFP/SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "LE Headset"
        else -> "(no seleccionable como casco)"
    }} | id=${device.id}"
