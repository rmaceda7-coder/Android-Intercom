package com.ramiromaceda.intercom

data class ValidationReset(val reason: String, val framesBeforeReset: Int, val observedAtNs: Long)
data class VoiceValidationResult(
    val currentFrames: Int, val bestFrames: Int, val completed: Boolean,
    val framesAtUnlock: Int?, val bestFramesAtUnlock: Int?,
    val resetCount: Int, val recentResets: List<ValidationReset>, val message: String,
    val lastProgressReset: ValidationReset?
) {
    fun describe(): String = buildString {
        fun seconds(frames: Int) = "%.2f".format(frames / 16000.0)
        appendLine("Validación estricta: ${if (completed) "60/60 s completados" else "NO completada"}; actual=${seconds(currentFrames)} s; máximo continuo=${seconds(bestFrames)} s.")
        framesAtUnlock?.let { appendLine("Al salir del bloqueo: ${seconds(it)} s actuales; máximo de ese bloqueo=${seconds(bestFramesAtUnlock ?: it)} s.") }
        appendLine("Estado del validador: $message. Reinicios observados: $resetCount.")
        lastProgressReset?.let { appendLine("Último progreso perdido: ${seconds(it.framesBeforeReset)} s; motivo exacto: ${it.reason} (t=${it.observedAtNs} ns).") }
        recentResets.forEach { appendLine("Reinicio t=${it.observedAtNs} ns, tras ${seconds(it.framesBeforeReset)} s: ${it.reason}") }
    }
}

/** Bounded PCM evidence for ONE continuous observed lock interval; contains no Android APIs. */
class LockedVoiceWindow(private val rate: Int = 16000, private val seconds: Int = 60) {
    private val pcm = ShortArray(rate * seconds)
    var frames: Int = 0
        private set
    var lockEpochNs: Long? = null
        private set
    var sourceFromNs: Long = 0
        private set
    var sourceToNs: Long = 0
        private set
    var invalidation = "Esperando pantalla apagada y bloqueo."
        private set
    var completed = false
        private set
    private var bestFrames = 0
    private var bestThisLock = 0
    private var framesAtUnlock: Int? = null
    private var bestAtUnlock: Int? = null
    private var resetCount = 0
    private var lastProgressReset: ValidationReset? = null
    private val resets = ArrayDeque<ValidationReset>()

    fun result() = VoiceValidationResult(frames, bestFrames, completed, framesAtUnlock,
        bestAtUnlock, resetCount, resets.toList(), invalidation, lastProgressReset)

    fun observeScreen(locked: Boolean, nowNs: Long) {
        if (!locked) {
            if (lockEpochNs != null) {
                framesAtUnlock = frames
                bestAtUnlock = bestThisLock
                if (!completed) invalidate("Se desbloqueó o encendió la pantalla; validación parcial reiniciada (PCM escuchable conservado).", nowNs)
            }
            lockEpochNs = null
        } else if (!completed && lockEpochNs == null) { lockEpochNs = nowNs; bestThisLock = 0 }
    }

    fun invalidate(reason: String, nowNs: Long = System.nanoTime()) {
        if (completed) return
        resetCount++
        if (frames > 0) lastProgressReset = ValidationReset(reason, frames, nowNs)
        // Coalesce identical zero-progress failures, but never overwrite a positive-duration reset.
        if (frames > 0 || resets.lastOrNull()?.reason != reason) {
            if (resets.size == 8) resets.removeFirst()
            resets.addLast(ValidationReset(reason, frames, nowNs))
        }
        frames = 0
        sourceFromNs = 0
        sourceToNs = 0
        invalidation = reason
    }

    fun append(data: ShortArray, count: Int, frameStartNs: Long?, nowNs: Long,
               missingTimestampReason: String = "Timestamp de entrada ausente/no fiable; validación reiniciada."): ShortArray? {
        if (completed) return null
        val epoch = lockEpochNs ?: return null
        if (frameStartNs == null) { invalidate(missingTimestampReason, nowNs); return null }
        if (frameStartNs < epoch + 1_000_000_000L) return null // Exclude queued pre-lock PCM.
        if (frameStartNs + count * 1_000_000_000L / rate > nowNs) {
            invalidate("Timestamp de entrada en el futuro; intervalo reiniciado.", nowNs); return null
        }
        // Permit small timestamp jitter, never a whole 20 ms read block gap/overlap.
        if (frames > 0 && kotlin.math.abs(frameStartNs - (sourceFromNs + frames * 1_000_000_000L / rate)) > 1_000_000L) {
            val delta = frameStartNs - (sourceFromNs + frames * 1_000_000_000L / rate)
            invalidate("Discontinuidad de entrada: desfase=$delta ns (>1 ms); validación reiniciada.", nowNs)
            return null
        }
        if (frames == 0) sourceFromNs = frameStartNs
        val copy = minOf(count, pcm.size - frames)
        data.copyInto(pcm, frames, 0, copy)
        frames += copy
        bestFrames = maxOf(bestFrames, frames)
        bestThisLock = maxOf(bestThisLock, frames)
        sourceToNs = frameStartNs + copy * 1_000_000_000L / rate
        invalidation = "Capturando intervalo continuo…"
        if (frames == pcm.size) {
            completed = true
            return pcm // Ownership transfers once; it is never written again.
        }
        return null
    }
}
