package com.ramiromaceda.intercom

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.widget.*

/** The screen owns no audio: it observes service state and sends explicit controls. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var routes: LinearLayout
    private lateinit var status: TextView
    private lateinit var fileLabel: TextView
    private lateinit var selectionLabel: TextView
    private lateinit var start: Button
    private lateinit var voiceStart: Button
    private lateinit var listen: Button
    private lateinit var listenReason: TextView
    private val sampleButtons = mutableListOf<Pair<Button, () -> CaptureSample?>>()
    private var selectedId: Int? = null
    private var uri: Uri? = null
    private var resumed = false
    private var bound = false
    private var service: LocalAudioService? = null
    private var renderedRoutes: List<RouteOption>? = null
    private var renderedBusy: Boolean? = null
    private val permissions = arrayOf(Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as LocalAudioService.LocalBinder).service
            service?.refreshDevices()
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    private val update = object : Runnable {
        override fun run() {
            if (resumed) { render(); handler.postDelayed(this, 500) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = savedInstanceState?.getString("uri")?.let(Uri::parse)
        selectedId = savedInstanceState?.getInt("device", -1)?.takeIf { it >= 0 }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 32)
        }
        fun text(value: String) = TextView(this).apply {
            text = value; textSize = 17f; setPadding(0, 12, 0, 12); column.addView(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; setOnClickListener { action() }; column.addView(this)
        }
        text("Intercom Android — diagnóstico Solo voz").textSize = 23f
        text("Conecta y selecciona el casco e inicia Solo voz. Di ANTES, bloquea y di DURANTE, desbloquea y di DESPUÉS; luego detén. Escucha por separado el tramo anterior (últimos hasta 10 s) y el leído apagada/bloqueada (hasta 60 s), con ID de sesión y duración. El tono es solo control de salida. Ver/Copiar resumen muestra entrada, errores y niveles por segundo. Límite: 120 s; sin reproductor musical.")
        text("No hay enlace entre teléfonos ni monitorización en vivo del micrófono. La calidad HFP/LE depende del hardware. Las rutas informadas por Android no sustituyen la prueba acústica.")
        button("Conceder permisos / actualizar rutas") {
            if (hasPermissions()) service?.refreshDevices() else requestPermissions(permissions, 1)
        }
        text("Ensayo anterior MP3/WAV (opcional). No se usa en Solo voz ni representa la música externa del producto.")
        button("Elegir MP3 o WAV para ensayo anterior") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 2)
        }
        fileLabel = text(uri?.toString() ?: "Sin archivo seleccionado")
        selectionLabel = text("Sin casco seleccionado")
        routes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(routes)
        voiceStart = button("Iniciar Solo voz — prueba de bloqueo 60 s") { launch(false, true) }
        start = button("Ensayo anterior: MP3/WAV + captura") { launch(false) }
        button("Detener sesión") { service?.requestStop("Detenida desde la pantalla.") }
        fun sampleButton(label: String, source: () -> CaptureSample?) {
            val control = button(label) { source()?.let { launch(true, sampleId = it.sampleId) } }
            sampleButtons.add(control to source)
        }
        text("TRAMOS DE LA SESIÓN ACTUAL — habilitados al detener y liberar captura")
        sampleButton("Anterior al bloqueo (actual)") { DiagnosticState.currentVoice?.before }
        sampleButton("Leído apagada/bloqueada (actual)") { DiagnosticState.currentVoice?.blocked }
        text("MUESTRAS ANTERIORES CONSERVADAS — no son de la sesión actual")
        sampleButton("Anterior al bloqueo (sesión anterior)") { DiagnosticState.previousVoice?.before }
        sampleButton("Leído apagada/bloqueada (sesión anterior)") { DiagnosticState.previousVoice?.blocked }
        listen = button("Escuchar muestra capturada") { DiagnosticState.sample?.let { launch(true, sampleId = it.sampleId) } }
        listenReason = text("Escucha deshabilitada: esperando muestra y selección de casco.")
        status = text("Servicio inactivo.")
        button("Ver resumen de la prueba") {
            val report = TextView(this).apply {
                text = DiagnosticState.fullReport(); textSize = 12f; setTextIsSelectable(true); setPadding(16, 16, 16, 16)
            }
            AlertDialog.Builder(this).setTitle("Informe de sesión (snapshot)")
                .setView(ScrollView(this).apply { addView(report) })
                .setPositiveButton("Copiar") { _, _ -> copyReport(report.text.toString()) }
                .setNegativeButton("Cerrar", null).show()
        }
        button("Copiar resumen de la prueba") { copyReport(DiagnosticState.fullReport()) }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, LocalAudioService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume(); resumed = true; handler.post(update)
    }

    override fun onPause() {
        resumed = false; handler.removeCallbacks(update); super.onPause()
    }

    override fun onStop() {
        // Unbinding never stops the started foreground session.
        if (bound) unbindService(connection)
        bound = false; service = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("uri", uri?.toString())
        outState.putInt("device", selectedId ?: -1)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(code: Int, names: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, names, results)
        service?.refreshDevices()
        if (!hasPermissions()) Toast.makeText(this,
            "Habilita Micrófono, Dispositivos cercanos y Notificaciones en Ajustes. Después pulsa Iniciar; no se inicia automáticamente.", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Platform Activity result API retained for a dependency-free diagnostic")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK) return
        val picked = data?.data ?: return
        try {
            // Scope access to the one selected document; no broad storage permission.
            contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = contentResolver.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: picked.lastPathSegment.orEmpty()
            val mime = contentResolver.getType(picked).orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase()
            require(extension in listOf("mp3", "wav") || mime in listOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/wave")) {
                "Selecciona un MP3 o WAV."
            }
            uri = picked; fileLabel.text = name
        } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }

    private fun hasPermissions(playbackOnly: Boolean = false) = permissions
        .filter { !playbackOnly || it != Manifest.permission.RECORD_AUDIO }
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun copyReport(report: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Intercom diagnóstico", report))
        Toast.makeText(this, "Resumen copiado. Incluye IDs y telemetría, no PCM.", Toast.LENGTH_LONG).show()
    }

    private fun launch(sample: Boolean, voiceOnly: Boolean = false, sampleId: String? = null) {
        if (!resumed || !hasWindowFocus() || !hasPermissions(sample)) {
            Toast.makeText(this, "Inicia desde la pantalla visible con los permisos concedidos.", Toast.LENGTH_LONG).show()
            return
        }
        if (DiagnosticState.busy.get()) return
        if (DiagnosticState.captureReleaseUnconfirmed) {
            Toast.makeText(this, "Cierre del micrófono anterior no confirmado; no se permite iniciar audio.", Toast.LENGTH_LONG).show()
            return
        }
        val id = selectedId ?: return
        if (!sample && !voiceOnly && uri == null) return
        try {
            startForegroundService(Intent(this, LocalAudioService::class.java).apply {
                action = when { sample -> LocalAudioService.SAMPLE; voiceOnly -> LocalAudioService.VOICE; else -> LocalAudioService.START }
                putExtra("device", id)
                if (sample) putExtra("sampleId", sampleId ?: return)
                if (!sample && !voiceOnly) this.data = uri
            })
        } catch (e: Exception) { Toast.makeText(this, "No se pudo iniciar: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun render() {
        val devices = DiagnosticState.devices
        if (selectedId != null && devices.none { it.id == selectedId }) selectedId = null
        val busy = DiagnosticState.busy.get()
        if (renderedRoutes != devices || renderedBusy != busy) {
            renderedRoutes = devices; renderedBusy = busy
            routes.removeAllViews()
            devices.forEach { device ->
                routes.addView(Button(this).apply {
                    text = device.label
                    isEnabled = !busy && device.headset
                    setOnClickListener { selectedId = device.id; render() }
                })
            }
        }
        selectionLabel.text = "Casco: " + (devices.firstOrNull { it.id == selectedId }?.label ?: "sin seleccionar")
        start.isEnabled = !busy && !DiagnosticState.captureReleaseUnconfirmed && selectedId != null && uri != null && hasPermissions()
        voiceStart.isEnabled = !busy && !DiagnosticState.captureReleaseUnconfirmed && selectedId != null && hasPermissions()
        val blockers = mutableListOf<String>()
        if (busy) blockers.add("hay una sesión activa o recursos cerrándose; pulsa Detener sesión y espera a que termine")
        if (DiagnosticState.captureReleaseUnconfirmed) blockers.add("no se ha confirmado la liberación del micrófono anterior; escucha bloqueada por seguridad")
        if (selectedId == null) blockers.add(if (devices.none { it.headset }) "no hay un casco de comunicación disponible; conéctalo en Ajustes y actualiza rutas" else "selecciona un casco disponible")
        val missing = permissions.filter { it != Manifest.permission.RECORD_AUDIO && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.map {
            when (it) {
                Manifest.permission.RECORD_AUDIO -> "Micrófono"
                Manifest.permission.BLUETOOTH_CONNECT -> "Dispositivos cercanos"
                else -> "Notificaciones"
            }
        }
        if (missing.isNotEmpty()) blockers.add("faltan permisos: ${missing.joinToString()}")
        val notifications = getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("local_audio_diagnostic")?.importance == NotificationManager.IMPORTANCE_NONE) {
            blockers.add("habilita las notificaciones y el canal de diagnóstico para poder detener la escucha")
        }
        sampleButtons.forEachIndexed { index, (control, source) ->
            val clip = source()
            val origin = if (index < 2) "ACTUAL" else "ANTERIOR"
            val segment = if (index % 2 == 0) "antes del primer bloqueo" else "leído apagada/bloqueada"
            control.text = clip?.let { "Escuchar $origin: ${it.label()}" }
                ?: "$origin — $segment: esta sesión NO generó muestra de este tramo"
            control.isEnabled = blockers.isEmpty() && clip != null
        }
        val last = DiagnosticState.sample
        listen.text = last?.let { "Escuchar última muestra conservada: ${it.label()}" } ?: "Escuchar muestra capturada — no hay PCM guardado"
        if (last?.pcm?.isNotEmpty() != true) blockers.add("no hay PCM guardado: ${DiagnosticState.noSampleReason}")
        listen.isEnabled = blockers.isEmpty()
        listenReason.text = if (blockers.isEmpty()) "Escucha disponible. No exige completar la validación de 60 s; reproduce la última muestra guardada solo por el casco verificado." else
            "Escucha deshabilitada:\n• ${blockers.joinToString("\n• ")}"
        status.text = DiagnosticState.summary()
    }
}
