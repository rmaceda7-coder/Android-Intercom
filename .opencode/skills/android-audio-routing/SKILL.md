---
name: android-audio-routing
description: Verifica micrófono, salida Bluetooth y reproducción simultánea de voz y música en el intercomunicador Android.
---
Para Android 15–17, enumera getAvailableCommunicationDevices y usa setCommunicationDevice para solicitar la ruta. Comprueba el dispositivo efectivo de captura y reproducción mientras cada flujo está activo. Registra cambios y desconexiones. Libera la ruta, los recursos y el modo de audio al cerrar la sesión.

Comprueba voz y reproducción simultáneas con el casco real. Registra tipo de dispositivo, frecuencia de muestreo, cortes y cambios de calidad. Trata HFP/SCO y LE Audio según la capacidad observada del par. Con archivos locales, usa el selector de Android y decodifica fuera del hilo de audio. Para el MVP verifica mezcla local de música con voz recibida y evita reenviar la mezcla por la red.
