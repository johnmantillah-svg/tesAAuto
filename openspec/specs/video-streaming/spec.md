# video-streaming Specification

## Purpose
Gestionar la creación de una pantalla virtual secundaria en Android, la codificación por hardware de sus fotogramas y la transmisión dinámica adaptativa del flujo de video hacia el cliente web del vehículo.

## Requirements

### Requirement: VirtualDisplay Provisioning via Shizuku
The system SHALL create an independent virtual display surface (`VirtualDisplay`) using `DisplayManager` elevated through Shizuku ADB permissions, without mirroring the physical phone screen.

#### Scenario: Successful secondary display allocation
- **WHEN** the projection session starts and Shizuku authorization is active
- **THEN** the system allocates a `VirtualDisplay` configured with the target resolution (default 1280x720) and density, keeping the physical screen available for normal use

#### Scenario: Shizuku permission not granted
- **WHEN** Shizuku service is unavailable or permission is denied by the user
- **THEN** the system SHALL abort virtual display creation and present a user notification requesting ADB pairing via Shizuku

### Requirement: Hardware Video Encoding Pipeline
The system SHALL capture the virtual display surface and encode the frames using `MediaCodec` with hardware-accelerated H.264 (baseline profile) or HEVC (H.265) codecs.

#### Scenario: Hardware encoder initialization
- **WHEN** the `VirtualDisplay` surface is created
- **THEN** the system initializes `MediaCodec` in surface-input mode and begins emitting NAL units (SPS/PPS/IDR frames) to the streaming pipeline

### Requirement: Adaptive Framerate and Thermal Mitigation
The system SHALL dynamically throttle the encoding framerate between 30 FPS and 60 FPS based on network buffer saturation and device thermal status.

#### Scenario: Thermal threshold exceeded
- **WHEN** Android thermal status reports `THERMAL_STATUS_SEVERE` or higher
- **THEN** the video encoder dynamically lowers output framerate to 30 FPS and increases the I-frame interval to reduce CPU/GPU load

### Requirement: Hardware DRM Exclusion Handling
The system SHALL handle protected content (`FLAG_SECURE` or Widevine L1) gracefully without crashing the encoding pipeline when frames are blanked by the OS.

#### Scenario: User opens DRM-protected application
- **WHEN** an application with `FLAG_SECURE` is displayed on the `VirtualDisplay`
- **THEN** the system continues streaming the blanked frame buffer without throwing encoding errors or dropping the WebSocket connection
