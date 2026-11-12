---
name: offline-audio-link
description: Evalúa y desarrolla el transporte directo de audio en tiempo real entre los teléfonos Android del intercomunicador.
---
Prueba Wi-Fi Direct sin internet con ambos cascos activos. Confirma permisos según versión y mide conexión, pérdida, latencia de audio y reconexión. Si Bluetooth entre teléfonos es obligatorio, mide un enlace RFCOMM entre las apps con ambos cascos activos antes de comprometer el MVP.

Separa control de sesión de los flujos de voz y música. Incluye identidad de origen y secuencia. Acota la cola de audio y descarta paquetes obsoletos para evitar que crezca el retraso. Valida voz en ambos sentidos antes de incorporar la música. Para extender a grupos, conserva identidad por participante y la independencia del transporte.
