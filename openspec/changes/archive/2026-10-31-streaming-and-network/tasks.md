# Tasks

## 1. Setup y Estructura Base del Proyecto

- [x] 1.1 Configurar el proyecto Android base en Kotlin con Gradle Version Catalog y verificar compilación limpia
- [x] 1.2 Añadir dependencias de Ktor CIO Server, WebSockets, Coroutines y Rikka Shizuku API en `build.gradle.kts` y verificar resolución de dependencias

## 2. Servidor Embebido y Capa de Red Local

- [x] 2.1 Implementar la detección y enlace dinámico de socket a la interfaz `wlan0` del punto de acceso Wi-Fi y verificar respuesta en puerto local 8080
- [x] 2.2 Configurar el enrutador HTTP en Ktor para servir archivos estáticos (`index.html`, scripts) desde los assets de la app y verificar carga con prueba unitaria de ruta
- [x] 2.3 Implementar el endpoint WebSocket `/ws` para la sesión de streaming con manejo de eventos de conexión/desconexión y verificar handshake con cliente de prueba

## 3. Pantalla Virtual e Integración con Shizuku

- [x] 3.1 Integrar `rikka.shizuku:api` para verificar estado del servicio y solicitar permisos ADB en tiempo de ejecución, verificando log de autorización
- [x] 3.2 Implementar el creador de `VirtualDisplay` desacoplado de la pantalla física con resolución configurable (720p/1080p) y verificar asignación de Display ID válido

## 4. Pipeline de Codificación de Video por Hardware

- [x] 4.1 Configurar `MediaCodec` en modo surface-input con codificador H.264 Baseline / HEVC y verificar generación de SPS/PPS
- [x] 4.2 Conectar la superficie del `VirtualDisplay` a la entrada del encoder y serializar los paquetes NAL hacia el canal WebSocket
- [x] 4.3 Implementar el motor de FPS adaptativo (reducción a 30 FPS ante alertas de API `ThermalManager`) y verificar con simulador de estado térmico

## 5. Cliente Web para Navegador Tesla (HTML5 / WebCodecs)

- [x] 5.1 Crear la página `index.html` con visor Canvas a pantalla completa optimizado para el viewport de Tesla y verificar renderizado estático
- [x] 5.2 Implementar el cliente WebSocket en JavaScript con decodificación de video (`WebCodecs VideoDecoder` con fallback a demuxer ligero) y verificar visualización de fotogramas

## 6. Mapeo e Inyección de Eventos Táctiles

- [x] 6.1 Implementar listeners de JavaScript para eventos `touchstart`, `touchmove` y `touchend` normalizando coordenadas relativas (0.0 a 1.0)
- [x] 6.2 Implementar el procesador de entrada en Android que traduzca las coordenadas normalizadas a píxeles absolutos del `VirtualDisplay`
- [x] 6.3 Ejecutar la inyección del evento de toque mediante comandos de entrada de Shizuku/ADB o AccessibilityService y verificar interacción en app de prueba

## 7. Pruebas de Integración y Mitigaciones de Seguridad

- [x] 7.1 Probar la resiliencia del pipeline ante aplicaciones con `FLAG_SECURE` verificando que la emisión continúa con pantalla en negro sin provocar excepciones
- [x] 7.2 Realizar prueba de estrés de transmisión continua durante 30 minutos monitoreando consumo térmico y estabilidad de memoria

## Workflow follow-up

- Archivar el cambio con `openspec archive streaming-and-network` tras la validación final del prototipo funcional.
