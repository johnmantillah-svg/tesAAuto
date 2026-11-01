# Design

## Context

Para habilitar la duplicación de pantalla en vehículos Tesla sin hardware externo ni alteraciones de firmware, se implementa una arquitectura cliente-servidor distribuida localmente entre el smartphone Android y el navegador web Chromium de la MCU de Tesla. La solución prescinde de dependencias de red en la nube, operando íntegramente sobre el punto de acceso Wi-Fi local generado por el dispositivo móvil.

Ver `proposal.md` para la motivación y alcance general.

## Goals / Non-Goals

**Goals:**
- Servir una aplicación cliente HTML5/Canvas ligera al navegador Chromium de Tesla a través de un servidor embebido en Android enlazado a `wlan0`.
- Establecer un canal bidireccional WebSocket con latencia inferior a 80 ms para video y eventos táctiles.
- Generar una pantalla virtual secundaria (`VirtualDisplay`) desacoplada de la pantalla física del teléfono usando elevación no-root mediante Shizuku (ADB).
- Codificar la superficie de video por hardware mediante `MediaCodec` en perfiles H.264 / HEVC compatibles con el navegador del vehículo.
- Proporcionar soporte de entrada táctil fluido traduciendo coordenadas relativas de pantalla a coordenadas absolutas de la pantalla virtual.
- Adaptar la tasa de cuadros (30 FPS vs 60 FPS) según la capacidad de procesamiento de la MCU (Intel Atom vs AMD Ryzen) y la temperatura del dispositivo Android.

**Non-Goals:**
- Reproducción de contenidos protegidos por DRM por hardware (Widevine L1 en Netflix, Disney+, Prime Video), lo cual es una restricción infranqueable del kernel/TEE de Android.
- Conexión a través de servidores retransmisores en Internet o servicios cloud (la operación es estrictamente LAN/Hotspot local).
- Requerir permisos de superusuario (*root*) o desbloqueo del bootloader del dispositivo móvil.

## Decisions

### 1. Servidor Embebido: Ktor (Motor CIO) sobre Netty o NanoHTTPD
* **Decisión:** Emplear `Ktor CIO` en Kotlin para el servidor HTTP y WebSocket local.
* **Razón:** Integración nativa con Corrutinas de Kotlin, huella de memoria reducida (< 15 MB) y soporte directo para WebSockets asíncronos y no bloqueantes.
* **Alternativas consideradas:**
  * *NanoHTTPD:* Código antiguo, mantenimiento deficiente y soporte rudimentario para WebSockets de alta tasa de transferencia.
  * *Netty:* Excelente rendimiento, pero mayor consumo de memoria y tamaño de binario elevado para un servicio móvil.

### 2. Enlace Directo a Interfaz `wlan0` en lugar de `VpnService`
* **Decisión:** Enlazar los sockets de escucha directamente a la IP local de la interfaz de tethering (`wlan0`).
* **Razón:** Elimina la interferencia con servicios de VPN de terceros o bloqueadores de publicidad en el teléfono que colisionan con el uso de `VpnService`.
* **Alternativas consideradas:**
  * *VpnService local:* Abordaje legado de versiones tempranas de Tesor; simplifica el enrutamiento pero bloquea el uso simultáneo de otras VPNs en el teléfono.

### 3. Asignación de `VirtualDisplay` con Shizuku (ADB)
* **Decisión:** Utilizar el marco Shizuku (`rikka.shizuku:api`) para invocar llamadas privilegiadas de `DisplayManager` y ejecución de comandos `input`.
* **Razón:** Permite crear pantallas virtuales secundarias sin requerir root ni firmware personalizado, manteniéndose compatible con políticas de seguridad de Android.
* **Alternativas consideradas:**
  * *MediaProjection API estándar:* Solo permite duplicar (*mirroring*) la pantalla física principal, impidiendo usar el teléfono para otras tareas mientras se proyecta.
  * *Magisk / KernelSU (Root):* Inviable para la gran mayoría de usuarios finales y rompe la integridad SafetyNet/Play Integrity.

### 4. Renderizado en Tesla Chromium: HTML5 Canvas con WebCodecs / jmuxer
* **Decisión:** Servir un reproductor web con detección de capacidades: `WebCodecs API` como primera opción (para MCU AMD Ryzen en versiones de firmware recientes) con degradación a MSE (*Media Source Extensions*) o *jmuxer* sobre Canvas.
* **Razón:** Minimiza el retardo de decodificación en el navegador a menos de 20 ms.

## Risks / Trade-offs

* **[Riesgo] Disipación Térmica y Batería:** El hotspot Wi-Fi en 5 GHz combinado con captura de pantalla continua y codificación HEVC genera una carga térmica elevada.
  * *Mitigación:* Algoritmo de control adaptativo que atenúa la pantalla física al mínimo brillo y ajusta dinámicamente los FPS a 30 si la temperatura excede 42 °C.
* **[Riesgo] Reinicio de Conexión Shizuku tras Reboot:** Shizuku requiere reactivación por depuración inalámbrica si el teléfono se apaga o reinicia.
  * *Mitigación:* Guía de interfaz gráfica en la app con instrucciones claras de vinculación mediante depuración inalámbrica en un solo toque (Android 11+).
* **[Riesgo] Actualizaciones de Firmware de Tesla:** Cambios en las políticas del navegador Chromium de Tesla podrían restringir APIs de WebSocket o reproducción de medios no autenticados.
  * *Mitigación:* Mantener el protocolo de comunicación sobre estándares abiertos HTTP/1.1 y WebSocket RFC 6455 con soporte HTTPS autofirmado si la política de orígenes seguros lo exige.
