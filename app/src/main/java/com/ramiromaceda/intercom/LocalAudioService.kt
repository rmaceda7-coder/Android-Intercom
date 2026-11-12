package com.ramiromaceda.intercom

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/** Owns focus, communication routing, capture and playback independently of the screen. */
class LocalAudioService : Service() {
    companion object {
        const val START = "com.ramiromaceda.intercom.START_LOCAL"
        const val VOICE = "com.ramiromaceda.intercom.VOICE_LOCAL"
        const val SAMPLE = "com.ramiromaceda.intercom.PLAY_SAMPLE"
        const val STOP = "com.ramiromaceda.intercom.STOP_LOCAL"
        private const val CHANNEL = "local_audio_diagnostic"
        private const val NOTIFICATION = 10
        private const val RATE = 16000
        private const val SAMPLE_FRAMES = RATE * 3
        private const val MAX_MS = 120000L
    }

    inner class LocalBinder : Binder() { val service: LocalAudioService get() = this@LocalAudioService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var power: PowerManager
    private lateinit var keyguard: KeyguardManager
    private val cancellation = AtomicReference<String?>(null)
    private val playerLock = Any()
    private var worker: Thread? = null
    private var currentOperationId = "sin operación"
    @Volatile private var chosen: AudioDeviceInfo? = null
    @Volatile private var routeActive = false
    @Volatile private var flowing = false
    @Volatile private var music: MusicOutput? = null
    @Volatile private var tone: CommunicationTone? = null
    private var samplePlayer: AudioTrack? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val device = chosen
            if (device != null && removed.any { it.id == device.id }) requestStop("Ruta compartida (entrada y salida): casco desconectado.")
            refreshDevices()
        }
    }
    private val communicationListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (routeActive && device?.id != chosen?.id) requestStop("Ruta compartida (entrada y salida): se perdió o cambió el dispositivo de comunicación.")
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        power = getSystemService(PowerManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Diagnóstico de audio local", NotificationManager.IMPORTANCE_LOW)
        )
        audio.registerAudioDeviceCallback(deviceCallback, main)
        audio.addOnCommunicationDeviceChangedListener(mainExecutor, communicationListener)
        refreshDevices()
    }

    override fun onBind(intent: Intent): IBinder = binder

    fun refreshDevices() {
        try {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                DiagnosticState.devices = audio.availableCommunicationDevices.map {
                    RouteOption(it.id, routeLabel(it), isBluetoothHeadset(it))
                }
            } else DiagnosticState.devices = emptyList()
        } catch (e: Exception) {
            DiagnosticState.event("No se pudieron enumerar rutas: ${e.message}")
            DiagnosticState.devices = emptyList()
            if (worker != null) requestStop("Permiso o acceso a rutas perdido.")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            requestStop("Detenida desde la notificación.")
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START && intent?.action != SAMPLE && intent?.action != VOICE) {
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        // Prevent a destroyed instance's worker from racing a newly created instance.
        if (!DiagnosticState.busy.compareAndSet(false, true)) {
            if (worker == null) {
                // A different instance is still finishing. Resolve this FGS start explicitly.
                try {
                    startForeground(NOTIFICATION, notification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } catch (e: Exception) { DiagnosticState.event("Arranque rechazado durante cierre: ${e.message}") }
                finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
            }
            return START_NOT_STICKY
        }
        val playbackOnly = intent.action == SAMPLE
        val voiceOnly = intent.action == VOICE
        currentOperationId = UUID.randomUUID().toString()
        DiagnosticState.operationId = currentOperationId
        if (voiceOnly) DiagnosticState.beginVoice(currentOperationId)
        try {
            check(!DiagnosticState.captureReleaseUnconfirmed) { "Entrada: cierre anterior del micrófono sin confirmar; escucha e inicio bloqueados por seguridad." }
            val required = buildList {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.POST_NOTIFICATIONS)
                if (!playbackOnly) add(Manifest.permission.RECORD_AUDIO)
            }
            check(required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                "Faltan permisos. Vuelve a la pantalla visible y concédelos."
            }
            val manager = getSystemService(NotificationManager::class.java)
            check(manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
                "Habilita la notificación de diagnóstico para disponer de Detener sesión."
            }
            val id = intent.getIntExtra("device", -1)
            val device = audio.availableCommunicationDevices.firstOrNull { it.id == id && isBluetoothHeadset(it) }
                ?: error("El casco ya no está disponible; vuelve a seleccionarlo.")
            val saved = if (playbackOnly) {
                val requested = intent.getStringExtra("sampleId")
                (if (requested == null) DiagnosticState.sample else DiagnosticState.findSample(requested))
                    ?: error("La muestra solicitada ya no está en memoria; no se sustituye por otra automáticamente.")
            } else null
            val uri = if (!playbackOnly && !voiceOnly) intent.data ?: error("Selecciona un MP3 o WAV.") else null
            startForeground(NOTIFICATION, notification(playbackOnly,
                if (voiceOnly) "Solo voz local: micrófono y salida de diagnóstico; Detener sesión" else null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    if (playbackOnly) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            cancellation.set(null)
            chosen = device
            DiagnosticState.input = "sin medición en esta sesión"
            DiagnosticState.output = "sin medición en esta sesión"
            DiagnosticState.progress = ""
            DiagnosticState.failure = "ninguno"
            if (!playbackOnly) DiagnosticState.lockProgress = "Sin intervalo de bloqueo acreditado en esta operación."
            DiagnosticState.mode = when { playbackOnly -> "Escucha de muestra"; voiceOnly -> "Solo voz (diagnóstico local)"; else -> "Ensayo anterior MP3/WAV + captura" }
            if (voiceOnly) {
                DiagnosticState.strictValidation = null
                if (DiagnosticState.sample == null) DiagnosticState.noSampleReason = "Aún no se ha leído PCM con pantalla bloqueada y entrada efectiva del casco en esta sesión."
            }
            DiagnosticState.status = "Preparando ${DiagnosticState.mode}…"
            if (saved != null) DiagnosticState.status = "Preparando escucha EXACTA: ${saved.label()}"
            DiagnosticState.event("Inicio explícito: ${routeLabel(device)}; ${DiagnosticState.mode}")
            worker = Thread({ runSession(device, uri, saved, voiceOnly) }, "ForegroundLocalAudio").also { it.start() }
        } catch (e: Exception) {
            DiagnosticState.status = "No se inició: ${e.message}"
            if (DiagnosticState.sample == null) DiagnosticState.noSampleReason = DiagnosticState.status
            DiagnosticState.event(DiagnosticState.status)
            if (voiceOnly) {
                DiagnosticState.currentVoice?.telemetry?.event(DiagnosticState.status)
                DiagnosticState.endVoice(currentOperationId)
            }
            DiagnosticState.busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(playbackOnly: Boolean, overrideText: String? = null): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, LocalAudioService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Intercom Android — prueba local")
            .setContentText(overrideText ?: if (playbackOnly) "Escuchando muestra por el casco" else "Micrófono y música activos; límite 120 s")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Detener sesión", stop).build()).build()
    }

    fun requestStop(reason: String) {
        if (worker == null) return
        if (cancellation.compareAndSet(null, reason)) {
            DiagnosticState.event(reason)
            // Silence as soon as a loss/stop event arrives; worker owns actual teardown.
            music?.mute()
            try { tone?.mute() } catch (_: Exception) { }
            synchronized(playerLock) {
                try { samplePlayer?.setVolume(0f) } catch (_: Exception) { }
            }
        }
    }

    private fun checkCancelled() { cancellation.get()?.let { error(it) } }

    private fun checkSession(device: AudioDeviceInfo) {
        checkCancelled()
        check(audio.availableCommunicationDevices.any { it.id == device.id }) { "Ruta compartida (entrada y salida): casco desconectado." }
        check(audio.communicationDevice?.id == device.id) { "Ruta compartida (entrada y salida): comunicación fuera del casco." }
    }

    private fun actualRoute(actual: AudioDeviceInfo?, input: Boolean, expected: AudioDeviceInfo): Boolean {
        val label = routeLabel(actual)
        val previous = if (input) DiagnosticState.input else DiagnosticState.output
        if (previous != label) {
            if (input) DiagnosticState.input = label else DiagnosticState.output = label
            DiagnosticState.event("${if (input) "Entrada" else "Salida"}: $label")
        }
        if (actual == null) return false
        val matches = isBluetoothHeadset(actual) && actual.type == expected.type &&
            (input || actual.id == expected.id) &&
            (actual.address.isEmpty() || expected.address.isEmpty() || actual.address.equals(expected.address, true))
        if (!matches) {
            requestStop("${if (input) "Entrada" else "Salida"}: ruta efectiva fuera del casco seleccionado; ambos flujos detenidos.")
            checkCancelled()
        }
        return true
    }

    private fun runSession(device: AudioDeviceInfo, uri: Uri?, saved: CaptureSample?, voiceOnly: Boolean) {
        var recorder: AudioRecord? = null
        var recorderListener: AudioRouting.OnRoutingChangedListener? = null
        var importedFile: File? = null
        var oldMode: Int? = null
        var focusGranted = false
        var requestedRoute = false
        var wake: PowerManager.WakeLock? = null
        var failed = false
        var result = "Sesión finalizada."
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(if (saved == null && !voiceOnly) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) requestStop("Ruta compartida/foco: foco de audio perdido; ambos flujos detenidos.") }, main).build()
        try {
            checkCancelled()
            wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:LocalAudioDiagnostic").apply {
                setReferenceCounted(false)
                acquire(MAX_MS + 60000) // Includes bounded import, routing and decoder preparation.
            }
            if (saved == null && !voiceOnly) {
                DiagnosticState.status = "Importando documento local (15 s / 32 MiB máx.). Aún sin micrófono ni ruta activos."
                importedFile = LocalDocument.copy(this, uri ?: error("Falta archivo local."), ::checkCancelled)
            }
            checkCancelled()
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Ruta compartida/foco: no se concedió el foco de audio." }
            focusGranted = true
            checkCancelled()
            oldMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            check(audio.setCommunicationDevice(device)) { "Ruta compartida (entrada y salida): Android rechazó la ruta del casco." }
            requestedRoute = true
            val routeDeadline = SystemClock.elapsedRealtime() + 8000
            while (audio.communicationDevice?.id != device.id) {
                checkCancelled()
                check(audio.availableCommunicationDevices.any { it.id == device.id }) { "Ruta compartida: casco desconectado durante preparación." }
                check(SystemClock.elapsedRealtime() < routeDeadline) { "Ruta compartida: el casco no activó comunicación en 8 s." }
                Thread.sleep(20)
            }
            routeActive = true
            checkSession(device)
            if (saved != null) {
                playSample(saved, device, attributes)
                result = "Escucha de muestra finalizada."
            } else {
                val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Entrada: captura PCM16 mono 16 kHz no soportada." }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    error("Entrada: permiso de micrófono revocado antes de iniciar la captura.")
                }
                val capture = try {
                    AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(pcmFormat(true)).setBufferSizeInBytes(maxOf(minimum, 6400)).build()
                } catch (e: Exception) { error("Entrada: no se pudo crear AudioRecord: ${e.message}") }
                recorder = capture
                check(capture.state == AudioRecord.STATE_INITIALIZED) { "Entrada: no se inicializó la captura." }
                val inListener = AudioRouting.OnRoutingChangedListener { routing ->
                    if (flowing) inspectCallback(routing, true, device)
                }
                recorderListener = inListener
                capture.addOnRoutingChangedListener(inListener, main)
                if (voiceOnly) {
                    runVoiceDiagnostic(capture, device)
                } else {
                val player = MusicOutput()
                music = player
                player.prepare(importedFile ?: error("Falta copia local."), attributes, device,
                    { routing -> if (flowing) inspectCallback(routing, false, device) }, ::requestStop)
                val prepareDeadline = SystemClock.elapsedRealtime() + 15000
                while (!player.prepared.get()) {
                    checkSession(device)
                    check(SystemClock.elapsedRealtime() < prepareDeadline) { "El archivo no se preparó en 15 s; prueba un archivo local descargado." }
                    Thread.sleep(20)
                }
                capture.startRecording()
                check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "No se inició el micrófono." }
                checkCancelled(); player.start()
                // Start silent. Do not enable audible music until BOTH effective routes are known.
                val warmupDeadline = SystemClock.elapsedRealtime() + 3000
                val buffer = ShortArray(320)
                var warmupFrames = 0L
                while (true) {
                    checkSession(device)
                    val inputReady = actualRoute(capture.routedDevice, true, device)
                    val outputReady = actualRoute(player.route(), false, device)
                    check(capture.activeRecordingConfiguration?.isClientSilenced != true) { "Android silenció el micrófono." }
                    if (inputReady && outputReady) break
                    check(SystemClock.elapsedRealtime() < warmupDeadline) { "Android no confirmó las rutas efectivas; música mantenida en silencio." }
                    val count = capture.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Error de captura al preparar." }
                    warmupFrames += count
                    Thread.sleep(10)
                }
                flowing = true
                checkSession(device); player.volume(0.65f, ::checkCancelled)
                checkCancelled()
                DiagnosticState.status = "Música + captura simultáneas en servicio foreground. Puedes bloquear el teléfono."
                DiagnosticState.event("Flujos activos; PCM solicitado: mono 16000 Hz, no indica códec Bluetooth.")
                captureWhilePlaying(capture, player, device, buffer, warmupFrames)
                }
                result = "Límite local de 120 s alcanzado."
            }
        } catch (e: Exception) {
            result = cancellation.get() ?: "Sesión detenida: ${e.message ?: e.javaClass.simpleName}"
            // A completed sample remains available on a deliberate Stop; route/capture errors invalidate it.
            val deliberate = cancellation.get()?.startsWith("Detenida desde") == true
            failed = !deliberate
            if (failed) DiagnosticState.failure = result
        } finally {
            flowing = false
            routeActive = false
            music?.mute()
            try { tone?.mute() } catch (_: Exception) { }
            synchronized(playerLock) { try { samplePlayer?.setVolume(0f) } catch (_: Exception) { } }
            val cleanup = mutableListOf<String>()
            fun safely(name: String, action: () -> Unit) {
                try { action() } catch (e: Exception) { cleanup.add("$name: ${e.message}") }
            }
            recorder?.let { capture ->
                recorderListener?.let { listener -> safely("Listener entrada") { capture.removeOnRoutingChangedListener(listener) } }
                safely("Detener captura") { if (capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) capture.stop() }
                safely("Liberar captura") {
                    try { capture.release(); DiagnosticState.captureReleaseUnconfirmed = false }
                    catch (e: Exception) { DiagnosticState.captureReleaseUnconfirmed = true; throw e }
                }
            }
            tone?.let { output -> safely("Liberar salida de diagnóstico") { output.close() } }
            tone = null
            music?.let { player ->
                player.mute()
                safely("Liberar música") { player.close() }
                if (!player.released.get()) {
                    // A timeout is NOT a release. Do not clear routing or allow a new session
                    // while a vendor decoder might still own audible playback.
                    DiagnosticState.status = "Cierre del reproductor pendiente: ruta y exclusión retenidas por seguridad. Si persiste, detén la app desde Ajustes."
                    DiagnosticState.event(DiagnosticState.status)
                    safely("Aviso de cierre pendiente") {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION,
                            notification(false, "Cierre pendiente; consulta la pantalla de diagnóstico"))
                    }
                    while (!player.released.get()) Thread.sleep(100)
                    DiagnosticState.event("El reproductor confirmó finalmente su liberación.")
                }
            }
            music = null
            synchronized(playerLock) {
                samplePlayer?.let { player ->
                    safely("Detener muestra") { if (player.playState == AudioTrack.PLAYSTATE_PLAYING) player.pause() }
                    safely("Vaciar muestra") { player.flush() }
                    safely("Liberar muestra") { player.release() }
                }
                samplePlayer = null
            }
            if (requestedRoute) safely("Liberar ruta") { audio.clearCommunicationDevice() }
            oldMode?.let { mode -> safely("Restaurar modo") { if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = mode } }
            if (focusGranted) safely("Liberar foco") { audio.abandonAudioFocusRequest(focus) }
            wake?.let { lock -> safely("Liberar wake lock") { if (lock.isHeld) lock.release() } }
            importedFile?.let { file -> safely("Borrar copia musical temporal") { check(file.delete() || !file.exists()) { "No se pudo borrar la copia." } } }
            // A new failed operation must never erase a previously published eligible listening clip.
            if (failed && DiagnosticState.sample == null) DiagnosticState.noSampleReason = DiagnosticState.failure
            DiagnosticState.status = (cancellation.get() ?: result) + if (cleanup.isEmpty())
                " Captura, reproducción, foco, ruta y wake lock liberados." else " Errores de limpieza: ${cleanup.joinToString("; ")}"
            DiagnosticState.event(DiagnosticState.status)
            if (voiceOnly) {
                DiagnosticState.currentVoice?.takeIf { it.sessionId == currentOperationId }?.telemetry?.let {
                    it.event("FIN: ${DiagnosticState.status}")
                    it.flush(DiagnosticClock.nowNs())
                }
                DiagnosticState.endVoice(currentOperationId)
            }
            main.post {
                chosen = null
                worker = null
                DiagnosticState.busy.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                refreshDevices()
                stopSelf()
            }
        }
    }

    private fun inspectCallback(routing: AudioRouting, input: Boolean, expected: AudioDeviceInfo) {
        try {
            if (!actualRoute(routing.routedDevice, input, expected)) requestStop("${if (input) "Entrada" else "Salida"}: ruta efectiva no informada; ambos flujos detenidos.")
        } catch (_: Exception) { requestStop("${if (input) "Entrada" else "Salida"}: no se pudo verificar ruta efectiva.") }
    }

    private fun pcmFormat(input: Boolean) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(RATE).setChannelMask(if (input) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_OUT_MONO).build()

    /** No URI, LocalDocument, MusicOutput or MediaPlayer is touched by this path. */
    private fun runVoiceDiagnostic(capture: AudioRecord, device: AudioDeviceInfo) {
        val evidence = DiagnosticState.currentVoice?.takeIf { it.sessionId == currentOperationId }
            ?: error("Falta identidad de la sesión Solo voz.")
        val telemetry = evidence.telemetry
        telemetry.event("AudioRecord instance=${System.identityHashCode(capture)} audioSessionId=${capture.audioSessionId}; " +
            "dispositivo=${Build.MANUFACTURER}/${Build.MODEL} Android=${Build.VERSION.RELEASE} API=${Build.VERSION.SDK_INT}; solicitado=${routeLabel(device)}")
        val output = try {
            CommunicationTone(device, main) { routing -> if (flowing) inspectCallback(routing, false, device) }
        } catch (e: Exception) { error("Salida: no se pudo iniciar AudioTrack de diagnóstico: ${e.message}") }
        tone = output
        try { capture.startRecording() } catch (e: Exception) { error("Entrada: no se pudo iniciar captura: ${e.message}") }
        check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Entrada: el micrófono no inició la grabación." }
        val data = ShortArray(320)
        var clientSilenced: Boolean? = null
        fun readObserved(): Int {
            val startNs = DiagnosticClock.nowNs()
            telemetry.screen(power.isInteractive, keyguard.isKeyguardLocked, startNs)
            val result = try { capture.read(data, 0, data.size, AudioRecord.READ_NON_BLOCKING) }
            catch (e: Exception) {
                telemetry.event("AudioRecord.read lanzó ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            val endNs = DiagnosticClock.nowNs()
            clientSilenced = capture.activeRecordingConfiguration?.isClientSilenced
            telemetry.read(endNs, routeLabel(capture.routedDevice), routeLabel(audio.communicationDevice),
                routeLabel(output.route()), result, data, clientSilenced, capture.recordingState,
                audio.isMicrophoneMute, endNs - startNs)
            telemetry.screen(power.isInteractive, keyguard.isKeyguardLocked, endNs)
            return result
        }
        var totalFrames = 0L
        val warmupDeadline = SystemClock.elapsedRealtime() + 5000
        while (true) {
            checkSession(device)
            output.pump() // Silent frames make routedDevice observable while output is active.
            val inputReady = actualRoute(capture.routedDevice, true, device)
            val outputReady = actualRoute(output.route(), false, device)
            if (inputReady && outputReady) break
            check(SystemClock.elapsedRealtime() < warmupDeadline) {
                "${when { !inputReady && !outputReady -> "Entrada y salida"; !inputReady -> "Entrada"; else -> "Salida" }}: ruta efectiva no confirmada; volumen mantenido en cero."
            }
            val count = readObserved()
            if (count > 0) totalFrames += count
            Thread.sleep(10)
        }
        flowing = true
        output.enable {
            checkSession(device)
            check(actualRoute(capture.routedDevice, true, device)) { "Entrada: ruta desconocida al habilitar diagnóstico." }
            check(actualRoute(output.route(), false, device)) { "Salida: ruta desconocida; no habilitar volumen." }
        }
        DiagnosticState.status = "Solo voz sesión $currentOperationId: entrada instrumentada; tono de control continúa ante silencio/errores de lectura, pero no ante pérdida de ruta."
        DiagnosticState.event("Solo voz: AudioTrack confirmado antes de habilitar volumen; sin MediaPlayer ni retorno del micrófono.")
        val window = LockedVoiceWindow()
        val playable = PlayableLockedBuffer()
        val before = BeforeLockBuffer()
        val stamp = AudioTimestamp()
        val started = DiagnosticClock.nowMs()
        var lastRead = started
        var lastUpdate = 0L
        var lastScreen = ""
        var underruns = 0
        var peak = 0
        var everLocked = false
        var wasLocked = false
        var lockOrdinal = 0
        var lastInputIssue = ""
        var finishReason = "Fin del límite local de 120 s."
        fun publish(clip: ListeningClip?, blocked: Boolean = true) {
            if (clip == null || clip.pcm.isEmpty()) return
            val sample = CaptureSample(clip.pcm, blocked, clip.firstReadMs, clip.lastReadMs,
                note = "Solo voz: entrada elegible ${routeLabel(device)}. ${clip.notes} La escucha no acredita 60 s continuos.",
                sessionId = currentOperationId, segment = if (blocked) "LEÍDO APAGADA/BLOQUEADA #$lockOrdinal" else "ANTES DEL PRIMER BLOQUEO")
            DiagnosticState.publishVoice(currentOperationId, blocked, sample)
            telemetry.event("MUESTRA ${sample.label()} lecturasMonoMs=${clip.firstReadMs}..${clip.lastReadMs}; sin acreditar instante de origen")
            DiagnosticState.event("Guardados ${"%.2f".format(clip.pcm.size / RATE.toDouble())} s de PCM para escucha; validador independiente.")
        }
        fun reportValidation() {
            val result = window.result()
            DiagnosticState.strictValidation = result
            DiagnosticState.lockProgress = "PCM escuchable pendiente del intervalo: ${"%.2f".format(playable.frames / RATE.toDouble())} s (máx. 60). " +
                if (result.completed) "Validación estricta completada." else "Validación estricta aún no completada."
        }
        try {
            while (DiagnosticClock.nowMs() - started < MAX_MS) {
                checkSession(device)
                check(actualRoute(capture.routedDevice, true, device)) { "Entrada: ruta efectiva desconocida; ambos flujos detenidos." }
                check(actualRoute(output.route(), false, device)) { "Salida: ruta efectiva desconocida; ambos flujos detenidos." }
                val lostOutput = output.pump()
                if (lostOutput > 0) {
                    underruns += lostOutput
                    window.invalidate("Salida: underrun de diagnóstico; intervalo parcial descartado.")
                    playable.note("Se observó underrun de salida; no demuestra un hueco del micrófono.")
                }
                val now = DiagnosticClock.nowMs()
                val lockedBefore = keyguard.isKeyguardLocked && !power.isInteractive
                telemetry.screen(power.isInteractive, keyguard.isKeyguardLocked, DiagnosticClock.nowNs())
                if (lockedBefore && !wasLocked) {
                    lockOrdinal++
                    if (!everLocked) { publish(before.finish(), false); everLocked = true }
                    telemetry.event("INICIO intervalo de bloqueo #$lockOrdinal")
                    playable.note("Intervalo de bloqueo observado #$lockOrdinal; PCM leído, no origen temporal acreditado.")
                }
                wasLocked = lockedBefore
                val screen = "pantalla interactiva=${power.isInteractive}, bloqueo=${keyguard.isKeyguardLocked}"
                val screenChanged = screen != lastScreen
                if (screenChanged) { DiagnosticState.event(screen); lastScreen = screen }
                window.observeScreen(lockedBefore, System.nanoTime()) // Even if read returns zero.
                publish(playable.observeEligibility(lockedBefore)) // Publish on unlock even with no read.
                if (screenChanged) reportValidation()
                val count = readObserved()
                val inputIssue = when {
                    count < 0 -> "Entrada: AudioRecord.read error=$count; tono de control continúa (rutas verificadas)."
                    clientSilenced == true -> "Entrada: Android informa isClientSilenced=true; tono de control continúa sin eludir privacidad."
                    now - lastRead >= 3000 -> "Entrada: sin frames por 3000 ms o más; tono de control continúa (ver ventanas por segundo)."
                    else -> ""
                }
                if (inputIssue.isNotEmpty()) {
                    if (inputIssue != lastInputIssue || window.frames > 0) {
                        window.invalidate(inputIssue)
                        telemetry.event(inputIssue)
                    }
                    lastInputIssue = inputIssue
                    playable.note(inputIssue)
                    DiagnosticState.status = inputIssue
                    DiagnosticState.failure = inputIssue
                } else if (lastInputIssue.isNotEmpty()) {
                    telemetry.event("Entrada: recuperada la observación de reads sin error/silenciamiento; revisar niveles y muestra, no prueba voz.")
                    lastInputIssue = ""
                    DiagnosticState.status = "Solo voz: reads observados; tono de salida activo. Revisa RMS/pico y tramos de esta sesión."
                }
                if (count > 0) {
                    if (now - lastRead > 250) {
                        val reason = "Entrada: demora de lectura=${now - lastRead} ms (>250 ms); validación reiniciada."
                        window.invalidate(reason)
                        playable.note(reason)
                    }
                    lastRead = now
                    val firstFrame = totalFrames
                    totalFrames += count
                    for (i in 0 until count) peak = maxOf(peak, kotlin.math.abs(data[i].toInt()))
                    val lockedAfter = keyguard.isKeyguardLocked && !power.isInteractive
                    window.observeScreen(lockedBefore && lockedAfter, System.nanoTime())
                    // Never retain the block crossing a known screen or input-route transition.
                    val inputStillEligible = actualRoute(capture.routedDevice, true, device)
                    check(inputStillEligible) { "Entrada: ruta no informada después de leer; bloque excluido." }
                    checkCancelled()
                    publish(playable.observeEligibility(lockedBefore && lockedAfter && inputStillEligible))
                    if (lockedBefore && lockedAfter && inputStillEligible) {
                        publish(playable.append(data, count, now, DiagnosticClock.nowMs()))
                    } else if (!everLocked && !lockedBefore && !lockedAfter && inputStillEligible) {
                        before.append(data, count, now, DiagnosticClock.nowMs())
                    }
                    val timestampStatus = capture.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                    val hasTimestamp = timestampStatus == AudioRecord.SUCCESS
                    val ageNs = System.nanoTime() - stamp.nanoTime
                    val overrun = hasTimestamp && stamp.framePosition - firstFrame > capture.bufferSizeInFrames + RATE / 2
                    val timestampReason = when {
                        clientSilenced == true -> "Entrada silenciada: isClientSilenced=true (PCM retenido solo para diagnóstico)."
                        !hasTimestamp -> "Timestamp ausente: AudioRecord.getTimestamp devolvió $timestampStatus."
                        overrun -> "Discontinuidad/posible overrun: timestamp ahead=${stamp.framePosition - firstFrame} frames; buffer=${capture.bufferSizeInFrames} frames."
                        ageNs < 0 -> "Timestamp en el futuro: edad=$ageNs ns."
                        ageNs > 500_000_000L -> "Timestamp obsoleto: edad=$ageNs ns (>500 ms)."
                        else -> ""
                    }
                    val timestampFresh = timestampReason.isEmpty()
                    val frameNs = if (timestampFresh) stamp.nanoTime + (firstFrame - stamp.framePosition) * 1_000_000_000L / RATE else null
                    if (timestampReason.isNotEmpty()) playable.note(timestampReason)
                    val complete = window.append(data, count, frameNs, System.nanoTime(), timestampReason)
                    if (complete != null) {
                        DiagnosticState.event("Validador estricto completó 60 s continuos; PCM para escucha se conserva por separado.")
                    }
                }
                if (now - lastUpdate >= 250) {
                    reportValidation()
                    DiagnosticState.progress = "Solo voz sesión $currentOperationId: ${(now - started) / 1000} / 120 s; entrada=$totalFrames frames; salida underruns=$underruns; pico acumulado=$peak (no es el pico por segundo); $screen. Ver/Copiar resumen para RMS/pico por segundo."
                    lastUpdate = now
                }
                Thread.sleep(10)
            }
        } catch (e: Exception) {
            finishReason = cancellation.get() ?: e.message ?: "Otro fallo observado: ${e.javaClass.simpleName}"
            if (cancellation.get()?.startsWith("Detenida desde") != true) window.invalidate(finishReason)
            playable.note("Intervalo interrumpido: $finishReason; solo se conserva PCM previamente leído con entrada elegible.")
            telemetry.event("FALLO/PARADA: $finishReason")
            throw e
        } finally {
            try { output.mute() } catch (e: Exception) { DiagnosticState.event("Salida: fallo al silenciar: ${e.message}") }
            publish(playable.finish(finishReason))
            if (!everLocked) publish(before.finish(), false)
            telemetry.event("Fin de lectura: $finishReason")
            telemetry.flush(DiagnosticClock.nowNs())
            reportValidation()
            if (DiagnosticState.sample == null) {
                DiagnosticState.noSampleReason = "No se leyó PCM elegible durante pantalla apagada/bloqueo y entrada del casco. Fin: $finishReason"
            }
        }
    }

    private fun captureWhilePlaying(capture: AudioRecord, player: MusicOutput, device: AudioDeviceInfo, buffer: ShortArray, warmupFrames: Long) {
        val started = SystemClock.elapsedRealtime()
        val visibleSample = ShortArray(SAMPLE_FRAMES)
        val lockedSample = ShortArray(SAMPLE_FRAMES)
        var visibleCount = 0
        var lockedCount = 0
        var visibleStart = 0L
        var lockedStart = 0L
        var haveLockedSample = false
        var previousScreen = ""
        var lastRead = started
        var lastUpdate = 0L
        var totalFrames = 0L
        var lockEpochNs = 0L
        var sourceFromNs = 0L
        val stamp = AudioTimestamp()
        while (SystemClock.elapsedRealtime() - started < MAX_MS) {
            checkSession(device)
            check(actualRoute(capture.routedDevice, true, device)) { "Entrada efectiva desconocida; captura detenida." }
            check(actualRoute(player.route(), false, device)) { "Salida efectiva desconocida; música detenida." }
            check(capture.activeRecordingConfiguration?.isClientSilenced != true) { "Android silenció el micrófono; revisa privacidad y otras apps." }
            check(player.isPlaying()) { "El reproductor dejó de reproducir." }
            val now = SystemClock.elapsedRealtime()
            val lockedBefore = keyguard.isKeyguardLocked && !power.isInteractive
            if (!lockedBefore && !haveLockedSample) {
                lockedCount = 0; lockedStart = 0; lockEpochNs = 0
            } else if (lockedBefore && lockEpochNs == 0L) lockEpochNs = System.nanoTime()
            val screen = "interactiva=${power.isInteractive}, bloqueo=${keyguard.isKeyguardLocked}"
            if (screen != previousScreen) { DiagnosticState.event("Pantalla: $screen"); previousScreen = screen }
            // Read roughly real-time, discard most PCM; only two bounded 3-second windows are retained.
            val count = capture.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { "Error de captura PCM: $count" }
            check(now - lastRead < 3000) { "La captura no produjo muestras durante 3 s." }
            if (count > 0) {
                lastRead = now
                val firstFrame = warmupFrames + totalFrames
                totalFrames += count
                if (visibleCount < SAMPLE_FRAMES) {
                    if (visibleCount == 0) visibleStart = now
                    val copy = minOf(count, SAMPLE_FRAMES - visibleCount)
                    buffer.copyInto(visibleSample, visibleCount, 0, copy)
                    visibleCount += copy
                    if (visibleCount == SAMPLE_FRAMES && !haveLockedSample) {
                        DiagnosticState.sample = CaptureSample(visibleSample.copyOf(), false, visibleStart, now,
                            note = "Ensayo anterior MP3/WAV; tiempos de lectura elapsedRealtime ms, no telemetría Solo voz.",
                            sessionId = currentOperationId, segment = "ENSAYO MP3/WAV inicial")
                        DiagnosticState.event("Muestra inicial de 3 s disponible; aún sin evidencia de bloqueo.")
                    }
                }
                val lockedAfter = keyguard.isKeyguardLocked && !power.isInteractive
                if (!haveLockedSample && lockedBefore && lockedAfter) {
                    // Relate read frame positions to MONOTONIC capture timestamps, not read times alone.
                    val timestampOk = capture.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                    if (timestampOk) {
                        check(stamp.framePosition - firstFrame <= capture.bufferSizeInFrames + RATE / 2) {
                            "Posible overrun o discontinuidad de captura; muestra invalidada."
                        }
                    }
                    val frameNs = if (timestampOk) stamp.nanoTime + (firstFrame - stamp.framePosition) * 1_000_000_000L / RATE else 0L
                    val endNs = frameNs + count * 1_000_000_000L / RATE
                    val timestampFresh = timestampOk && System.nanoTime() - stamp.nanoTime in 0..500_000_000L
                    if (timestampFresh && frameNs >= lockEpochNs + 1_000_000_000L && endNs <= System.nanoTime()) {
                        if (lockedCount > 0 && kotlin.math.abs(frameNs - (sourceFromNs + lockedCount * 1_000_000_000L / RATE)) > 20_000_000L) {
                            DiagnosticState.event("Discontinuidad temporal de captura; se reinicia ventana del bloqueo.")
                            lockedCount = 0
                        }
                        if (lockedCount == 0) { lockedStart = now; sourceFromNs = frameNs }
                        val copy = minOf(count, SAMPLE_FRAMES - lockedCount)
                        buffer.copyInto(lockedSample, lockedCount, 0, copy)
                        lockedCount += copy
                        if (lockedCount == SAMPLE_FRAMES) {
                            haveLockedSample = true
                            DiagnosticState.sample = CaptureSample(lockedSample.copyOf(), true, lockedStart, now,
                                sourceFromNs, sourceFromNs + SAMPLE_FRAMES * 1_000_000_000L / RATE,
                                note = "Ensayo anterior MP3/WAV; tiempos de lectura elapsedRealtime ms.",
                                sessionId = currentOperationId, segment = "ENSAYO MP3/WAV bloqueado")
                            DiagnosticState.event("Ventana de 3 s capturada con bloqueo confirmado. Escuchar después de detener.")
                        }
                    } else {
                        lockedCount = 0; lockedStart = 0
                    }
                } else if (!haveLockedSample) {
                    lockedCount = 0; lockedStart = 0; lockEpochNs = 0
                }
            }
            if (now - lastUpdate >= 500) {
                DiagnosticState.progress = "Tiempo activo: ${(now - started) / 1000} s / 120; muestras leídas=$totalFrames; $screen"
                lastUpdate = now
            }
            Thread.sleep(10)
        }
    }

    private fun playSample(saved: CaptureSample, device: AudioDeviceInfo, attributes: AudioAttributes) {
        val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Formato de reproducción no soportado." }
        val track = AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(pcmFormat(false))
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 3200)).build()
        synchronized(playerLock) { samplePlayer = track }
        check(track.state == AudioTrack.STATE_INITIALIZED) { "No se inicializó la salida de muestra." }
        val listener = AudioRouting.OnRoutingChangedListener { if (flowing) inspectCallback(it, false, device) }
        track.addOnRoutingChangedListener(listener, main)
        try {
            track.setVolume(0f)
            check(track.setPreferredDevice(device)) { "Salida: no se pudo preferir el casco para escuchar la muestra." }
            track.play()
            val silent = ShortArray(320)
            val limit = SystemClock.elapsedRealtime() + 3000
            while (!actualRoute(track.routedDevice, false, device)) {
                checkSession(device)
                check(SystemClock.elapsedRealtime() < limit) { "No se confirmó la salida; muestra mantenida en silencio." }
                check(track.write(silent, 0, silent.size, AudioTrack.WRITE_NON_BLOCKING) >= 0) { "Error preparando salida." }
                Thread.sleep(10)
            }
            // Drop the silent warm-up queue and reset playback head before real samples.
            flowing = false
            track.pause(); track.flush(); track.play()
            // Flush/restart may change routing: verify the active sink AGAIN while still silent.
            val recheckDeadline = SystemClock.elapsedRealtime() + 3000
            var silentWritten = 0
            while (!actualRoute(track.routedDevice, false, device)) {
                checkSession(device)
                check(SystemClock.elapsedRealtime() < recheckDeadline) { "Salida: ruta efectiva no confirmada tras reiniciar; muestra silenciada." }
                val count = track.write(silent, 0, silent.size, AudioTrack.WRITE_NON_BLOCKING)
                check(count >= 0) { "Salida: error al confirmar ruta de escucha." }
                silentWritten += count
                Thread.sleep(10)
            }
            val warmupFrames = silentWritten
            flowing = true
            synchronized(playerLock) {
                checkSession(device)
                check(actualRoute(track.routedDevice, false, device)) { "Salida: ruta no verificada; no habilitar volumen de muestra." }
                track.setVolume(0.8f)
            }
            DiagnosticState.status = "REPRODUCIENDO ${saved.label()}; operación de escucha $currentOperationId. Micrófono detenido."
            DiagnosticState.event(DiagnosticState.status)
            var offset = 0
            val deadline = SystemClock.elapsedRealtime() + saved.pcm.size * 1000L / RATE + 5000
            var lastProgress = 0L
            while (offset < saved.pcm.size || track.playbackHeadPosition - warmupFrames < saved.pcm.size) {
                checkSession(device)
                check(actualRoute(track.routedDevice, false, device)) { "Salida efectiva perdida." }
                check(SystemClock.elapsedRealtime() < deadline) { "Reproducción sin progreso." }
                if (offset < saved.pcm.size) {
                    val count = track.write(saved.pcm, offset, minOf(320, saved.pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { "Error reproduciendo muestra: $count" }
                    offset += count
                }
                if (SystemClock.elapsedRealtime() - lastProgress >= 500) {
                    DiagnosticState.progress = "Escucha de muestra: ${maxOf(0, track.playbackHeadPosition - warmupFrames) / RATE} / ${"%.2f".format(saved.pcm.size / RATE.toDouble())} s; sin captura de micrófono."
                    lastProgress = SystemClock.elapsedRealtime()
                }
                Thread.sleep(10)
            }
            DiagnosticState.event("Escucha completada; underruns=${track.underrunCount}")
        } finally {
            flowing = false
            track.removeOnRoutingChangedListener(listener)
        }
    }

    override fun onDestroy() {
        requestStop("Servicio destruido; sesión cancelada sin reinicio automático.")
        audio.unregisterAudioDeviceCallback(deviceCallback)
        audio.removeOnCommunicationDeviceChangedListener(communicationListener)
        super.onDestroy()
    }
}
