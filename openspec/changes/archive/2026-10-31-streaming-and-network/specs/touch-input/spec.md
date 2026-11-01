# Spec Delta

## Purpose

Capturar, serializar y transformar eventos táctiles generados en la pantalla táctil del vehículo Tesla para su inyección precisa en el sistema Android como eventos de entrada sobre el display virtual.

## ADDED Requirements

### Requirement: Browser Touch Event Capture and Serialization
The HTML5 client application SHALL capture JavaScript touch events (`touchstart`, `touchmove`, `touchend`, `touchcancel`) on the video rendering canvas and serialize them to binary or JSON payloads over WebSocket.

#### Scenario: User touches the Tesla central screen
- **WHEN** a touch interaction occurs within the video canvas bounds
- **THEN** the client normalizes touch coordinates (0.0 to 1.0) and transmits an input message over the WebSocket connection within 10 milliseconds

### Requirement: Touch Coordinate Mapping and Injection
The Android host application SHALL deserialize incoming touch messages, map normalized coordinates to the absolute pixel resolution of the `VirtualDisplay`, and inject the input events via Shizuku ADB shell or AccessibilityService.

#### Scenario: Inbound touch event received
- **WHEN** a valid touch event message arrives at the `/ws` endpoint
- **THEN** the system translates normalized coordinates `(x, y)` to `VirtualDisplay` coordinates `(X_px, Y_px)` and delivers a synthetic motion event to the target display

#### Scenario: Touch outside canvas bounds
- **WHEN** an interaction event contains coordinates outside the valid projection viewport
- **THEN** the system MUST discard the packet without injecting invalid touch events into the operating system
