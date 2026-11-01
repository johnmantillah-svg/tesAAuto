# Proposal

## Why

Los vehículos Tesla carecen de compatibilidad nativa con plataformas de proyección como Android Auto, obligando a los conductores a recurrir a soportes externos para teléfonos o modificaciones invasivas de hardware. Esta propuesta establece la base arquitectónica y funcional de un sistema de proyección inalámbrica local y bidireccional entre un dispositivo Android y el navegador web Chromium integrado en la pantalla central de Tesla, prescindiendo de servidores en la nube y sin requerir permisos de superusuario (*root*).

## What Changes

- Implementación de un servidor local HTTP y WebSocket embebido en el dispositivo Android vinculado directamente a la interfaz de anclaje (*tethering*) local `wlan0`.
- Creación de un display virtual secundario (`VirtualDisplay`) independiente de la pantalla física del teléfono utilizando elevación de privilegios no invasiva mediante el marco Shizuku (ADB).
- Implementación de un pipeline de captura y codificación de video por hardware con la API `MediaCodec` (formatos H.264 y HEVC/H.265) con tasa de cuadros adaptativa (30 FPS en MCU Intel Atom y 60 FPS en MCU AMD Ryzen).
- Despliegue de un cliente web autónomo en HTML5/JavaScript servido al navegador de Tesla para renderizar el flujo multimedia en un elemento Canvas / WebCodecs.
- Canal bidireccional de eventos táctiles serializados desde el navegador web del automóvil e inyectados en Android.
- Delimitación explícita de exclusión para contenido protegido por esquemas de gestión de derechos digitales (DRM Widevine L1).

## Capabilities

### New Capabilities
- `network-server`: Servidor HTTP y WebSocket embebido en Android enlazado a la interfaz `wlan0` sobre punto de acceso Wi-Fi 5 GHz para distribución local del cliente web y transporte bidireccional de datos.
- `video-streaming`: Creación de `VirtualDisplay` secundario, codificación por hardware mediante `MediaCodec` (H.264/HEVC), adaptación térmica dinámica de FPS y multiplexación del flujo de video.
- `touch-input`: Captura de eventos táctiles (`touchstart`, `touchmove`, `touchend`) en el cliente web del vehículo, transmisión por WebSocket y traducción a coordenadas absolutas para inyección en Android vía Shizuku/ADB o AccessibilityService.

### Modified Capabilities

## Impact

- **Código y Plataforma Android:** Nueva aplicación modular en Kotlin orientada a Android 10+ (API 29+), con servicio en primer plano (*Foreground Service*) y dependencias de `Ktor CIO` y `rikka.shizuku:api`.
- **Cliente Web Embebido:** Aplicación frontend ligera (HTML5, JavaScript vanilla y WebCodecs/Canvas) alojada en los assets de la app Android.
- **Rendimiento y Térmico:** Incremento en el consumo de batería y temperatura del dispositivo móvil debido a la combinación de anclaje Wi-Fi 5 GHz y codificación continua por hardware, mitigado por algoritmos de reducción dinámica de tasa de refresco y atenuación de pantalla.
- **Restricciones de Terceros:** Las aplicaciones con flag de seguridad `FLAG_SECURE` o protegidas por DRM Widevine L1 (Netflix, Prime Video, Disney+) emitirán pantalla negra por diseño de hardware de Android.
