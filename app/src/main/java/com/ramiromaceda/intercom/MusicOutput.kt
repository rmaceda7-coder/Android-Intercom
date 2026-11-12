package com.ramiromaceda.intercom

import android.media.*
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Every MediaPlayer call and callback belongs to this Looper, never the capture/UI thread. */
class MusicOutput {
    private val thread = HandlerThread("LocalMusicDecoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var player: MediaPlayer? = null
    val prepared = AtomicBoolean(false)
    val released = AtomicBoolean(false)

    private fun <T> call(block: () -> T): T {
        val task = FutureTask<T> { block() }
        check(handler.post(task)) { "El hilo del reproductor ya terminó." }
        return task.get(3, TimeUnit.SECONDS)
    }

    fun prepare(file: File, attributes: AudioAttributes, device: AudioDeviceInfo,
                onRoute: (AudioRouting) -> Unit, onError: (String) -> Unit) = call {
        val active = MediaPlayer()
        player = active
        active.setAudioAttributes(attributes)
        active.setVolume(0f, 0f)
        check(active.setPreferredDevice(device)) { "El reproductor rechazó la salida preferida del casco." }
        active.isLooping = true
        active.setOnPreparedListener { prepared.set(true) }
        active.setOnErrorListener { _, what, extra -> onError("Error MP3/WAV: $what/$extra"); true }
        active.addOnRoutingChangedListener({ routing -> onRoute(routing) }, handler)
        active.setDataSource(file.absolutePath)
        active.prepareAsync()
    }

    fun start() = call { player!!.start() }
    fun volume(value: Float, checkAllowed: () -> Unit) = call { checkAllowed(); player!!.setVolume(value, value) }
    fun route(): AudioDeviceInfo? = call { player!!.routedDevice }
    fun isPlaying(): Boolean = call { player!!.isPlaying }
    fun mute() {
        handler.post { try { player?.setVolume(0f, 0f) } catch (_: Exception) { } }
    }
    fun close() {
        try { call { player?.release(); player = null; released.set(true) } }
        finally { thread.quitSafely() }
    }
}
