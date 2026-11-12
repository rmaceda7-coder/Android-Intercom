package com.ramiromaceda.intercom

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Provider access is bounded separately, BEFORE acquiring microphone/focus/route. */
object LocalDocument {
    private val importing = AtomicBoolean(false)
    fun copy(context: Context, uri: Uri, cancelled: () -> Unit): File {
        check(importing.compareAndSet(false, true)) { "La importación anterior sigue cerrándose; intenta con un documento local." }
        val signal = CancellationSignal()
        val abort = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val result = AtomicReference<File?>(null)
        val failure = AtomicReference<Exception?>(null)
        val io = Thread({
            var file: File? = null
            try {
                val destination = File.createTempFile("local-music-", ".audio", context.cacheDir)
                file = destination
                context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { asset ->
                    asset.createInputStream().use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(32768)
                            var total = 0L
                            while (true) {
                                check(!abort.get()) { "Importación cancelada." }
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= 32L * 1024 * 1024) { "Archivo demasiado grande: límite local 32 MiB." }
                                output.write(buffer, 0, count)
                            }
                            check(total > 0) { "Archivo vacío." }
                        }
                    }
                } ?: error("No se pudo abrir el archivo local.")
                if (!abort.get()) result.set(file)
            } catch (e: Exception) { failure.set(e) }
            finally {
                if (abort.get() || failure.get() != null) file?.delete()
                importing.set(false)
                done.countDown()
            }
        }, "LocalDocumentImport").apply { isDaemon = true; start() }
        try {
            val limit = SystemClock.elapsedRealtime() + 15000
            while (!done.await(20, TimeUnit.MILLISECONDS)) {
                cancelled()
                check(SystemClock.elapsedRealtime() < limit) { "Importación agotó 15 s. Selecciona un archivo descargado localmente." }
            }
            cancelled()
            failure.get()?.let { throw it }
            return result.get() ?: error("Importación cancelada.")
        } catch (e: Exception) {
            abort.set(true)
            result.get()?.delete()
            // A faulty provider can block native I/O: never retain audio resources while waiting for it.
            Thread({ signal.cancel(); io.interrupt() }, "CancelDocumentImport").apply { isDaemon = true; start() }
            throw e
        }
    }
}
