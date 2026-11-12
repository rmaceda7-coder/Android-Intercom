package com.ramiromaceda.intercom

/** Last 10 s before the FIRST observed lock; no post-lock audio is added to this segment. */
class BeforeLockBuffer(private val capacity: Int = 16000 * 10) {
    private data class ReadMark(val endFrame: Long, val fromMs: Long)
    private val pcm = ShortArray(capacity)
    private val marks = ArrayDeque<ReadMark>()
    private var total = 0L
    private var lastMs = 0L
    private var closed = false

    fun append(data: ShortArray, count: Int, fromMs: Long, toMs: Long) {
        if (closed || count <= 0) return
        for (i in 0 until count) pcm[((total + i) % capacity).toInt()] = data[i]
        total += count
        marks.addLast(ReadMark(total, fromMs))
        while (marks.isNotEmpty() && marks.first().endFrame <= total - capacity) marks.removeFirst()
        lastMs = toMs
    }

    fun finish(): ListeningClip? {
        if (closed) return null
        closed = true
        val size = minOf(total, capacity.toLong()).toInt()
        if (size == 0) return null
        val result = ShortArray(size) { index -> pcm[((total - size + index) % capacity).toInt()] }
        return ListeningClip(result, marks.first().fromMs, lastMs,
            "Últimos hasta 10 s leídos ANTES del primer bloqueo observado; no incluye audio posterior al bloqueo.")
    }
}
