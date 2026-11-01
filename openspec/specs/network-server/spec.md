# network-server Specification

## Purpose
Proveer la capa de comunicación y servidor web/WebSocket local en el dispositivo Android para servir el reproductor HTML5 y habilitar el intercambio bidireccional de flujos multimedia y eventos táctiles con el navegador del vehículo Tesla.

## Requirements

### Requirement: Local Web Server Initialization
The system SHALL start an embedded HTTP server bound specifically to the local hotspot wireless network interface (`wlan0`) on a configurable non-privileged port (default 8080).

#### Scenario: Server successfully bound to Wi-Fi hotspot
- **WHEN** hotspot tethering is active on 5 GHz and the projection service starts
- **THEN** the HTTP server binds to the local IP address on interface `wlan0` and responds with HTTP 200 OK to root requests

#### Scenario: Interface wlan0 unavailable
- **WHEN** the hotspot interface `wlan0` is not active or has no IP address assigned
- **THEN** the server MUST emit a state error event and transition the service to an inactive waiting state without crashing

### Requirement: HTML5 Client Delivery
The embedded HTTP server SHALL serve a self-contained single-page HTML5/JavaScript application when a client requests the root URL or static asset paths.

#### Scenario: Tesla browser requests projection page
- **WHEN** the Tesla MCU Chromium browser issues an HTTP GET request to `http://<phone-ip>:8080/`
- **THEN** the server returns the HTML5 web player with `Content-Type: text/html` and appropriate cache headers

### Requirement: Bidirectional WebSocket Channel
The system SHALL establish and maintain a WebSocket endpoint at `/ws` for streaming video/audio data and receiving serialized input control events.

#### Scenario: WebSocket handshake completion
- **WHEN** the web client initiates a WebSocket connection to `ws://<phone-ip>:8080/ws`
- **THEN** the server completes the protocol upgrade handshake and registers the client session as the active projection sink

#### Scenario: Client disconnection handling
- **WHEN** the WebSocket connection with the Tesla MCU is terminated or dropped
- **THEN** the server MUST release associated streaming buffers, notify the video encoder to pause keyframe generation, and wait for reconnection
