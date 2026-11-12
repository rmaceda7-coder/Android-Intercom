# Evidencia — Solo voz HFP/SCO: sesión, tramos y telemetría de entrada

Fecha: 2026-11-11. Sustituye los resultados y el hash del build anterior.
Alcance: diagnóstico local para un teléfono y un casco; no conversación remota.

## Estado y procedencia de la evidencia

Se utilizaron el APK, los XML JUnit y el informe de lint **ya generados**. En este
cierre **no se volvió a ejecutar Gradle**, no se modificó código de aplicación y
no se instalaron APKs ni se hicieron pruebas físicas.

En la ejecución anterior registrada:

- `./gradlew.bat :app:assembleDebug`: **BUILD SUCCESSFUL in 13s**.
- `./gradlew.bat :app:testDebugUnitTest :app:lintDebug`: **BUILD SUCCESSFUL in 13s**.
- La compilación emitió un aviso de versiones XML del SDK: el procesador comprende
  hasta versión 3 y encontró XML versión 4. No impidió generar el APK.

La compilación y las pruebas JVM no demuestran que el micrófono HFP/SCO continúe
capturando voz durante el bloqueo. **El fallo reportado no se considera resuelto.**

## APK existente verificado

Ruta absoluta:

```text
D:\Dev\Projects\IntercomAndroid\intercom-android-opencode\app\build\outputs\apk\debug\app-debug.apk
```

- Archivo existente: **2.619.679 bytes**.
- SHA-256 calculado sobre ese archivo, sin recompilar:

```text
DF38CE2DE42F1BB09553750FC0D3C78DF3A8B8AAB3054E75467603EAA28F6D5F
```

Este hash identifica el artefacto que debe instalarse para la prueba física. No
utilizar los hashes de builds anteriores como evidencia de esta instrumentación.

## Pruebas unitarias existentes

Fuente: `app/build/test-results/testDebugUnitTest/TEST-*.xml`.
Los cinco XML tienen fecha de ejecución `2026-11-11T23:40:47Z` aproximadamente.

| Suite | Tests | Fallos | Errores | Omitidos |
| --- | ---: | ---: | ---: | ---: |
| `BeforeLockBufferTest` | 2 | 0 | 0 | 0 |
| `LockedVoiceWindowTest` | 11 | 0 | 0 | 0 |
| `PlayableLockedBufferTest` | 5 | 0 | 0 | 0 |
| `VoiceSessionIdentityTest` | 2 | 0 | 0 | 0 |
| `VoiceTelemetryTest` | 3 | 0 | 0 | 0 |
| **Total** | **23** | **0** | **0** | **0** |

La cobertura JVM incluye separación y retención de tramos, orden del buffer previo,
IDs de origen, rechazo de IDs inexistentes, conservación de muestras anteriores,
ventana estricta, estadísticas RMS/pico reiniciadas por ventana, códigos de lectura,
silenciamiento desconocido y límites de la telemetría.

**No cubre** rutas reales, captura Bluetooth, Android foreground services, permisos,
interacción con botones, clipboard ni el comportamiento del micrófono al bloquear.

## Lint existente

- Resultado del informe: **0 errores y 5 avisos**.
- Avisos: versión más reciente de Gradle disponible; reglas `dataExtractionRules`;
  icono de aplicación no definido; dos avisos `SetTextI18n` de la selección de casco.
- Texto: `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.
- HTML: `app/build/reports/lint-results-debug.html`.

## Qué permite observar este build

- UUID de operación/sesión y de muestra. Etiquetas de escucha con tramo, sesión,
  duración y muestra; sesión actual y muestras anteriores se muestran por separado.
- Tramo previo: últimos **hasta 10 s antes del primer bloqueo observado**.
- Tramo bloqueado: primeros **hasta 60 s de PCM leído con pantalla apagada/bloqueada**
  y entrada efectiva compatible. Distintos bloqueos no se concatenan; una muestra
  no vacía de un bloqueo posterior puede sustituir el tramo bloqueado actual.
- Ambos tramos de la misma sesión se conservan en memoria y pueden escucharse al
  detener y liberar captura. La reproducción solicita un `sampleId` explícito y
  verifica la salida efectiva del casco antes de habilitar volumen.
- Si no hay muestra nueva del tramo actual, se indica; una anterior no se etiqueta
  como nueva. La validación estricta de 60 s sigue siendo un resultado independiente.
- Telemetría con ventanas aproximadamente de un segundo y duración real: ruta de
  entrada/comunicación/salida, frames y acumulado, reads positivos/cero, errores por
  código, RMS/pico del intervalo, proporción de ceros, recordingState, mic mute global
  e `isClientSilenced` verdadero/falso/desconocido. No se interpreta configuración
  ausente como `false`.
- Transiciones de pantalla observadas, eventos y ventanas usan MONOTONIC
  (`System.nanoTime`), la misma base que `AudioTimestamp.TIMEBASE_MONOTONIC`.
  Un hueco del worker se muestra con su duración real, no como segundos inventados.
- El tono independiente continúa ante retornos negativos/cero o silenciamiento de
  entrada mientras las rutas y la salida sigan verificadas. No elude privacidad.
  Pérdida de casco/ruta/foco o fallo de salida sigue cancelando la prueba.

**Límite esencial:** “PCM leído bloqueado” no acredita su instante acústico de origen;
puede contener audio encolado previamente. El tono es control de salida, no prueba
del micrófono. Sin dirección Bluetooth visible, la entrada solo se correlaciona por
perfil y requiere comprobar físicamente que la voz procede del casco.

## Dónde ver o copiar el resumen

En la pantalla del diagnóstico:

1. **Ver resumen de la prueba** abre un snapshot seleccionable del informe.
2. **Copiar resumen de la prueba** copia el informe actual al portapapeles.
3. El diálogo también incluye **Copiar** para copiar el snapshot que está mostrando.

El informe incluye IDs, metadatos de muestras y telemetría; **no incluye PCM**. Se
conserva en memoria del proceso, no se exporta automáticamente a un archivo y se
pierde si muere el proceso. Copiarlo tras Stop y **antes de iniciar otra prueba**.
La traza por sesión está acotada a 512 entradas e indica cuántas se descartaron.

## Limitaciones detectadas en revisión estática, aún no corregidas

1. Una sesión fallida sin muestras puede perder su telemetría al comenzar otra:
   la retención de sesión anterior está condicionada a que haya PCM. **Copiar el
   resumen del fallo antes de iniciar una nueva sesión**, incluso si no produjo audio.
2. Los listeners de botones vuelven a consultar la fuente al pulsar. En una ventana
   de cambio de estado anterior al siguiente refresco, el ID enviado podría diferir
   del renderizado. El servicio sí respeta el ID recibido y rechaza uno inexistente;
   queda pendiente fijar la acción al ID renderizado y probar esa carrera.
3. Un retorno negativo de `read()` se instrumenta y permite seguir al tono, pero una
   **excepción lanzada** por `read()` se registra y propaga, deteniendo también la
   salida. No afirmar que el tono sobrevive a toda clase de fallo de entrada.

Estos hallazgos son estáticos: no se presentan como fallos observados en dispositivos
ni se ocultan por el resultado positivo de compilación/tests/lint.

## Prueba física pendiente — fallo HFP/SCO reportado

**Reporte del propietario:** al bloquear, el tono continúa por el casco y la muestra
solo permite oír voz previa al bloqueo. No se repitió físicamente en este entorno;
no se aporta una nueva observación ADB o de hardware en este cierre.

Registrar teléfono, Android/API/build, casco/perfil HFP/SCO y SHA-256 instalado.
Comprobar en Android 15–17 según hardware disponible, sin extrapolar entre pares.

1. Instalar este APK, conceder permisos, conectar/seleccionar el casco e iniciar
   Solo voz sin archivo. Anotar el ID de sesión y mantener el teléfono lejos del casco.
2. Decir una frase identificable **ANTES** durante unos segundos; bloquear con
   pantalla apagada, decir otra **DURANTE**, mantener el bloqueo 60–70 s y escuchar
   si el tono de control continúa. Anotar cualquier corte sin atribuirlo al micrófono.
3. Desbloquear, decir una tercera frase **DESPUÉS**, esperar 5–10 s y pulsar Stop.
   La telemetría posterior debe ayudar a observar recuperación sin mezclar el PCM
   posterior con los dos tramos de escucha solicitados.
4. Copiar el informe inmediatamente, antes de otra prueba. Correlacionar transición,
   ruta de entrada, frames/readErrors, RMS/pico e `isClientSilenced` por segundo.
5. Escuchar por separado **anterior** y **apagada/bloqueada de la misma sesión**,
   verificando ID y duración del botón y del estado de reproducción. Confirmar salida
   por el casco y micrófono detenido. No confundir muestras anteriores conservadas.
6. Repetir con bloqueo corto, varios bloqueos, nueva sesión sin PCM y Stop desde
   notificación. Verificar retención y mensajes de ausencia de tramo actual.
7. Desconectar casco/cambiar ruta durante captura o escucha: comprobar cancelación,
   liberación y cualquier fuga breve al teléfono. Los callbacks son asíncronos.

**Aceptación pendiente:** identificar si el fallo corresponde a muestra antigua,
silenciamiento, cero frames, error de lectura, cambio de entrada o PCM encolado;
demostrar con voz real qué ocurre durante el bloqueo. Esta instrumentación no
acredita todavía una reparación HFP/SCO ni conversación remota.
