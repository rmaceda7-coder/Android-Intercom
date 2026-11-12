# Intercom Android

App privada Android en Kotlin con un módulo `app` y diagnóstico de audio local
para un teléfono y un casco Bluetooth. Este build añade **Solo voz**: micrófono del
casco y señal de comunicación independiente, sin inicializar MediaPlayer ni importar
música. No implementa voz remota, música compartida ni enlace de red.

## Producto previsto (no implementado por este diagnóstico)

Conectar casco en Ajustes → conceder permisos y verificar ruta → descubrir/seleccionar
otro teléfono y aceptar ambos → seleccionar modo → iniciar y observar/detener sesión.
Mantener independientes el enlace entre teléfonos y el Bluetooth de cada casco.

- **Reproducir música:** fuente iniciada en un reproductor externo, sin activar
  deliberadamente el micrófono de conversación.
- **Conversación en tiempo real:** voz bidireccional, sin capturar música.
- **Ambos:** voz bidireccional y una sola fuente musical externa compartida.

Cualquiera puede originar música. Deben continuar hablando y escuchando al bloquear
o desbloquear cualquiera de los dos teléfonos en Android 15–17. La lista de usuarios,
aceptación, transporte y captura autorizada del reproductor externo se probarán por
separado. No se supone que todo reproductor permita captura. Solo voz NO es una
conversación remota ni acredita el requisito con dos teléfonos bloqueados.

## Diagnóstico Solo voz — muestra escuchable y validación independientes

1. Emparejar/conectar casco en Ajustes, conceder Micrófono, Dispositivos cercanos
   y Notificaciones, y seleccionar su ruta HFP/SCO o LE Headset disponible.
2. Sin elegir archivo, pulsar **Iniciar Solo voz — prueba de bloqueo 60 s**.
3. Verificar la entrada y la salida efectivas que informa Android con los flujos
   activos. La salida AudioTrack empieza silenciada; se habilita únicamente tras
   confirmar ambas rutas. Son pulsos suaves de 440 Hz, 100 ms cada 2 s; no es un
   retorno del micrófono ni voz remota.
4. Bloquear con pantalla apagada y hablar. Puede ser una prueba corta: al salir del
   bloqueo o detener se guardan los primeros **hasta 60 s de PCM leído**, comprobando
   entrada efectiva del casco antes y después de cada lectura. No exige timestamps.
5. Desbloquear, revisar duración y procedencia de la muestra, detener mediante
   pantalla o notificación y pulsar **Escuchar muestra capturada**. No hace falta
   aprobar la validación estricta. La escucha no activa el micrófono.
6. Para intentar la prueba estricta, mantener bloqueo unos **65 s** con frases al
   principio, mitad y final. Ese validador excluye un segundo inicial y exige 60 s
   continuos de frames con timestamps válidos y flujos sanos.

La muestra ocupa como máximo 1.920.000 bytes de PCM16 mono y solo permanece en memoria. No se
guarda ni se transmite. No hace falta selector de música ni se crea MusicOutput o
MediaPlayer en este modo. Se utiliza el mismo servicio foreground y la misma acción
**Detener sesión**, con límite de banco de 120 s para intentar completar la prueba.

**Dos resultados separados:** el PCM audible se conserva aunque falten timestamps,
no se complete el minuto o haya un reinicio estricto. Solo se guardan lecturas bajo
bloqueo observado con entrada compatible; eso no acredita que su instante de captura
de origen sea posterior al bloqueo (puede existir audio previamente encolado).
Se informa duración con decimales, tiempos de lectura y procedencia observada.
Un nuevo bloqueo crea otro candidato; nunca se concatenan distintos bloqueos.
Una sesión vacía o fallida no sustituye ni borra la última muestra no vacía.

El validador estricto calcula progreso por frames, no por tiempo de sesión, y muestra
actual, máximo alcanzado, actual/máximo al salir del bloqueo y causas de reinicio.
Desbloqueo, timestamp ausente (con código de retorno), obsoleto (con edad), futuro,
discontinuidad (>1 ms, con desfase), lectura demorada o underrun de salida reinician
ese resultado parcial **sin borrar el audio escuchable**. El motivo no desaparece
al aceptar nuevas lecturas: se conserva un historial acotado y el último reinicio
que perdió progreso. La correlación temporal depende del driver y no equivale a
validación acústica ni garantía de cero cortes.

Si Escuchar está deshabilitado, la pantalla enumera los motivos: sesión/cierre aún
activo, ningún PCM guardado y su causa, falta de casco seleccionado/disponible,
permisos Bluetooth/notificaciones o canal deshabilitados. Escuchar no necesita
permiso de micrófono ni completar el minuto. Sí requiere captura anterior liberada;
si el cierre no se confirma, nuevos inicios y escucha quedan bloqueados por seguridad.
La salida se solicita al casco y se verifica otra vez tras reiniciar el AudioTrack,
siempre silenciada hasta entonces. Perder la ruta detiene la reproducción.

Se informa **Entrada**, **Salida** o **Ruta compartida (entrada y salida)** al fallar;
por seguridad se aborta la sesión local completa, se silencian salidas y se liberan
recursos. La pérdida de foco también aborta; reconectar no reinicia la captura.

## Ensayo anterior MP3/WAV + captura (opcional)

Este selector sigue siendo un ensayo local anterior, no la fuente musical del
producto ni captura de un reproductor externo. No se utiliza en Solo voz.

1. Emparejar y conectar el casco desde Ajustes de Android.
2. Conceder Micrófono, Dispositivos cercanos y Notificaciones. No se realiza búsqueda
   Bluetooth. La notificación debe estar habilitada para ofrecer **Detener sesión**.
3. Seleccionar una ruta HFP/SCO o LE Headset disponible. Las otras rutas se
   muestran como información; un dispositivo A2DP exclusivo no es seleccionable.
4. Elegir un MP3 o WAV descargado mediante el selector de Android. Se importa una
   copia temporal privada: límite 32 MiB y espera 15 s; se borra al finalizar.
5. Pulsar **Iniciar música + captura** desde la pantalla visible con permisos.
   Revisar entrada/salida efectivas y escuchar música mientras se habla por el casco.
6. Bloquear el teléfono y hablar al menos cinco segundos; desbloquear y revisar si
   se obtuvo la muestra del bloqueo. Cambiar de pantalla o recrear la Activity no
   debe detener la sesión. Límite de banco: 120 s de flujos simultáneos.
7. Detener desde la pantalla o la notificación y pulsar **Escuchar muestra capturada**.
   La escucha es una nueva operación explícita y utiliza otra vez la ruta del casco.

Las ventanas del ensayo anterior PCM16 mono de 16 kHz se guardan solo en memoria (48.000 muestras cada
una). Se conserva inicialmente una muestra sin evidencia de bloqueo y se sustituye
por una ventana de tres segundos del bloqueo, si hay timestamps válidos. Se descarta
un segundo tras detectar pantalla apagada y bloqueo; se exige que los frames de
AudioRecord correspondan a tiempos MONOTONIC posteriores a ese límite. Si Android
no entrega timestamps válidos o se desbloquea antes, no se acredita esa ventana.
Los tiempos de lectura se muestran separados de los timestamps de captura. Solo
escuchar la voz confirma contenido acústico. Las muestras se pierden si muere el
proceso; no se escriben en disco ni se transmiten. Detener manualmente conserva una
muestra completa; un fallo de captura/ruta no borra automáticamente PCM ya publicado.

La frecuencia solicitada no indica el códec ni la frecuencia nativa Bluetooth.
El servicio foreground de tipos microphone/mediaPlayback es dueño de AudioRecord,
MediaPlayer, AudioTrack para la escucha, foco, modo, ruta y un partial wake lock
acotado. La Activity solo muestra estado y envía controles. MediaPlayer tiene un
hilo propietario con Looper; la decodificación y el acceso a documentos no se hacen
en el hilo de captura. El servicio no reinicia automáticamente una captura tras
muerte del proceso. Al terminar libera recursos, foco, ruta y wake lock, y restaura
el modo previo. **Bloquear o salir de la pantalla no detiene el servicio iniciado.**
Detener, perder foco o perder el casco sí cancela la operación.
La música comienza silenciada y solo se habilita después de verificar las dos rutas
efectivas. HFP puede reducir notablemente la calidad musical. El archivo se repite
durante la prueba, sin monitorizar el micrófono local en directo.
Si un decoder nativo no confirma su liberación, se muestra **cierre pendiente**:
no se habilita otra sesión ni se despeja deliberadamente la ruta mientras pueda
existir audio residual. Un atasco persistente requiere detener la app desde Ajustes.
Se comprueban rutas durante los flujos; no se pretende continuar por el teléfono.
La detección de cambios es asíncrona: probar físicamente posibles fugas breves de
audio al desconectar. No garantiza exclusividad del hardware frente a otras apps.

Los IDs de entrada y salida pueden diferir. Si Android no expone direcciones,
solo puede correlacionarse el perfil Bluetooth, no demostrar identidad entre dos
cascos del mismo perfil. La comprobación acústica sigue siendo obligatoria.
La captura silenciada por Android se rechaza cuando el sistema informa ese estado;
capturar muestras no demuestra que contengan voz audible.

## Compilar

- Android Gradle Plugin 9.1.1 con Kotlin integrado y Gradle Wrapper 9.3.1.
- JDK 17 o compatible (entorno inicial: Temurin 21.0.12.1).
- Android SDK Platform 37.0, con `platforms/android-37.0/android.jar`.
- Build Tools predeterminadas de AGP: 36.0.0; no requiere 37.x por compartir
  el número mayor con la plataforma.
- `minSdk = 35`, `targetSdk = 37`, `compileSdk` release 37, minor 0.

Configurar `sdk.dir` en `local.properties` con la ruta del SDK de cada equipo.
Ese archivo está excluido de Git. Después ejecutar:

```powershell
.\gradlew.bat :app:assembleDebug
```

Salida esperada: `app/build/outputs/apk/debug/app-debug.apk`, firmada con clave
debug; no es una versión de producción. La primera compilación necesita internet
para descargar Gradle y las dependencias. No se requieren permisos de red en la app.

Compatibilidad documentada:
https://developer.android.com/build/releases/agp-9-1-0-release-notes

La instalación y el diagnóstico deben probarse en Android 15, 16 y 17 con hardware
real. Registrar modelo de teléfono, versión Android, modelo de casco y perfil.
Verificar permisos denegados/revocados, entrada del casco aislando el teléfono,
salida acústica, desconexión durante preparación/captura/reproducción, detener,
rotación, bloqueo de pantalla, pérdida de foco y reconexión con nueva selección.
Confirmar que la ruta queda disponible para otras apps y repetir ciclos. El enlace
entre teléfonos y la música con voz remota activa corresponden a hitos posteriores.
El requisito del MVP es mantener voz y música compartida en ambos teléfonos al
bloquear/desbloquear cualquiera; esta prueba local no lo acredita.

Evidencia de compilación y banco pendiente: [VALIDATION.md](VALIDATION.md).

## Creador

**Ramiro Maceda — Senior Software Quality Assurance Engineer**

- Teléfono: +591 60356760
- Email: rmaceda7@gmail.com
- LinkedIn: https://www.linkedin.com/in/ramiro-maceda-toranzos-928746214
- GitHub: https://github.com/rmaceda7-coder
- Repositorio: https://github.com/rmaceda7-coder/Android-Intercom

Datos canónicos de autoría en [AUTHORS.md](AUTHORS.md).
