---
name: android-apk-validation
description: Compila y verifica el APK privado y documenta pruebas físicas del intercomunicador Android.
---
Verifica el SDK efectivo y ejecuta assembleDebug con el Gradle Wrapper. Comprueba el archivo APK y, si hay un dispositivo disponible, usa ADB para instalarlo y abrirlo. Para pruebas iniciales usa firma debug. Antes de distribuir actualizaciones privadas estables, conserva el identificador del paquete y usa una clave de firma bajo control del propietario.

Verifica que el APK declare minSdk 35 y targetSdk 37. Usa Android 15, 16 y 17 para comprobar instalación y arranque, según los entornos disponibles. Para validar Bluetooth y audio usa hardware real y deja explícitas las combinaciones pendientes. En banco registra modelo y versión de Android y modelo de casco por cada par. Prueba micrófono y salida efectivos, desconexión y reconexión. En hitos posteriores comprueba ambos sentidos de voz, música iniciada desde cada teléfono, bloqueo de pantalla y recuperación del enlace. Distingue los resultados observados de los pasos pendientes.
