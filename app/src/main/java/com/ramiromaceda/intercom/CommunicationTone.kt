package com.ramiromaceda.intercom

import android.media.*
import android.os.Handler
import android.os.SystemClock
import kotlin.math.sin

/** Gentle independent 440 Hz pulses, not a microphone loopback. AudioTrack only. */
class CommunicationTone(device: AudioDeviceInfo, handler: Handler, private val onRoute: (AudioRouting) -> Unit) {
    private val lock = Any()
    private val track: AudioTrack
    private val listener = AudioRouting.OnRoutingChangedListener { onRoute(it) }
    private val pending = ShortArray(320)
    private var offset = 320
    private var written = 0L
    private var audible = false
    private var lastHead = 0
    private var lastProgress = SystemClock.elapsedRealtime()
    private var initialUnderruns = 0
    private var monitoredUnderruns = 0
    private var closed = false

    init {
        val minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Salida: formato PCM de comunicación no soportado." }
        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 3200)).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Salida: AudioTrack no inicializado." }
            track.setVolume(0f)
            check(track.setPreferredDevice(device)) { "Salida: el casco no acepta salida preferida." }
            track.addOnRoutingChangedListener(listener, handler)
            track.play()
        } catch (e: Exception) { track.release(); throw e }
    }

    fun route(): AudioDeviceInfo? = synchronized(lock) { track.routedDevice }

    fun enable(checkRoutesAndCancellation: () -> Unit) = synchronized(lock) {
        checkRoutesAndCancellation()
        initialUnderruns = track.underrunCount
        monitoredUnderruns = initialUnderruns
        lastHead = track.playbackHeadPosition
        lastProgress = SystemClock.elapsedRealtime()
        audible = true
        track.setVolume(0.4f) // PCM amplitude below is 3%; never full-scale feedback.
    }

    fun mute() = synchronized(lock) { if (!closed) track.setVolume(0f) }

    /** Non-blocking, retains partial writes, and watches actual hardware head progress. */
    fun pump(): Int = synchronized(lock) {
        check(!closed) { "Salida: AudioTrack cerrado." }
        if (offset == pending.size) {
            for (i in pending.indices) {
                val frame = written + i
                val pulse = frame % 32000 < 1600 // 100 ms every 2 s; a quiet independent test signal.
                pending[i] = if (audible && pulse) (sin(2.0 * Math.PI * 440 * frame / 16000) * 980).toInt().toShort() else 0
            }
            offset = 0
        }
        val count = track.write(pending, offset, pending.size - offset, AudioTrack.WRITE_NON_BLOCKING)
        check(count >= 0) { "Salida: error de escritura AudioTrack $count." }
        offset += count
        written += count
        val head = track.playbackHeadPosition
        if (head != lastHead) { lastProgress = SystemClock.elapsedRealtime(); lastHead = head }
        // The service owns the 5-second silent routing warm-up deadline.
        if (audible) check(SystemClock.elapsedRealtime() - lastProgress < 2000) { "Salida: sin avance de reproducción durante 2 s." }
        val underruns = track.underrunCount
        val newUnderruns = underruns - monitoredUnderruns
        monitoredUnderruns = underruns
        newUnderruns
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            try {
                track.setVolume(0f)
                track.removeOnRoutingChangedListener(listener)
                track.pause()
                track.flush()
            } finally { track.release(); closed = true }
        }
    }
}
