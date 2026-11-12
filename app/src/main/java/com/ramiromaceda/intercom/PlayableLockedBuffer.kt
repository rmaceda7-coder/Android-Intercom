package com.ramiromaceda.intercom

data class ListeningClip(val pcm: ShortArray, val firstReadMs: Long, val lastReadMs: Long, val notes: String)

/** PCM eligibility is independent of AudioTimestamp/strict validation. Never joins lock intervals. */
class PlayableLockedBuffer(private val maxFrames: Int = 16000 * 60) {
    private val pcm = ShortArray(maxFrames)
    var frames = 0
        private set
    private var eligible = false
    private var firstReadMs = 0L
    private var lastReadMs = 0L
    private var publishedAtCap = false
    private val notes = linkedSetOf<String>()

    fun observeEligibility(value: Boolean): ListeningClip? {
        val saved = if (eligible && !value) finish("Fin del intervalo de pantalla apagada/bloqueo o entrada elegible.") else null
        eligible = value
        return saved
    }

    fun note(reason: String) {
        if (notes.size < 8) notes.add(reason)
    }

    fun append(data: ShortArray, count: Int, readStartMs: Long, readEndMs: Long): ListeningClip? {
        if (!eligible || count <= 0 || frames == maxFrames) return null
        if (frames == 0) firstReadMs = readStartMs
        val copy = minOf(count, maxFrames - frames)
        data.copyInto(pcm, frames, 0, copy)
        frames += copy
        lastReadMs = readEndMs
        if (frames == maxFrames && !publishedAtCap) {
            publishedAtCap = true
            return snapshot("Límite de 60 s de PCM leído alcanzado; no implica validación estricta.")
        }
        return null
    }

    private fun snapshot(reason: String) = ListeningClip(pcm.copyOf(frames), firstReadMs, lastReadMs,
        (notes + reason).joinToString("; "))

    fun finish(reason: String): ListeningClip? {
        val saved = if (frames > 0) snapshot(reason) else null
        frames = 0; firstReadMs = 0; lastReadMs = 0; publishedAtCap = false
        eligible = false; notes.clear()
        return saved
    }
}
