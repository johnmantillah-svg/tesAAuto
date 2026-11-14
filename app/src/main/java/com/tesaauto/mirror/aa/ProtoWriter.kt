package com.tesaauto.mirror.aa

import java.io.ByteArrayOutputStream

/**
 * Serializador Protobuf de alto rendimiento y cero dependencias para el protocolo OAA.
 */
class ProtoWriter {
    private val buffer = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = buffer.toByteArray()

    fun writeVarint(value: Long) {
        var v = value
        while (true) {
            val toWrite = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) {
                buffer.write(toWrite or 0x80)
            } else {
                buffer.write(toWrite)
                break
            }
        }
    }

    fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    fun writeInt32(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, 0)
        writeVarint(value.toLong())
    }

    fun writeInt64(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, 0)
        writeVarint(value)
    }

    fun writeBool(fieldNumber: Int, value: Boolean) {
        writeTag(fieldNumber, 0)
        writeVarint(if (value) 1L else 0L)
    }

    fun writeString(fieldNumber: Int, value: String) {
        if (value.isNotEmpty()) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeTag(fieldNumber, 2)
            writeVarint(bytes.size.toLong())
            buffer.write(bytes)
        }
    }

    fun writeBytes(fieldNumber: Int, bytes: ByteArray) {
        if (bytes.isNotEmpty()) {
            writeTag(fieldNumber, 2)
            writeVarint(bytes.size.toLong())
            buffer.write(bytes)
        }
    }

    fun writeMessage(fieldNumber: Int, messageBytes: ByteArray) {
        if (messageBytes.isNotEmpty()) {
            writeTag(fieldNumber, 2)
            writeVarint(messageBytes.size.toLong())
            buffer.write(messageBytes)
        }
    }

    fun writePackedVarints(fieldNumber: Int, list: List<Int>) {
        if (list.isNotEmpty()) {
            val temp = ProtoWriter()
            for (v in list) {
                temp.writeVarint(v.toLong())
            }
            val packedBytes = temp.toByteArray()
            writeTag(fieldNumber, 2)
            writeVarint(packedBytes.size.toLong())
            buffer.write(packedBytes)
        }
    }

    companion object {
        /**
         * Construye la respuesta completa de ServiceDiscovery de Android Auto Head Unit
         * compatible al 100% con Desktop Head Unit / HUR / Waze.
         */
        fun buildServiceDiscoveryResponse(
            width: Int = 1280,
            height: Int = 720,
            dpi: Int = 120
        ): ByteArray {
            // 1. Servicio 1: Input Source Service (Canal 1: Touchscreen capacitiva y Keycodes)
            val ts = ProtoWriter().apply {
                writeInt32(1, width)
                writeInt32(2, height)
                writeInt32(3, 1) // CAPACITIVE
            }.toByteArray()

            val keycodes = listOf(3, 4, 19, 20, 21, 22, 23, 5, 6, 84, 85, 87, 88)
            val inputSvc = ProtoWriter().apply {
                writePackedVarints(1, keycodes)
                writeMessage(2, ts)
            }.toByteArray()

            val s1 = ProtoWriter().apply {
                writeInt32(1, 1) // Service ID 1
                writeMessage(4, inputSvc) // field 4 = input_source_service
            }.toByteArray()

            // 2. Servicio 2: Sensor Source Service (Canal 2: Sensores vehiculares requeridos)
            val sensorDriving = ProtoWriter().apply { writeInt32(1, 13) }.toByteArray() // SENSOR_DRIVING_STATUS_DATA
            val sensorNight = ProtoWriter().apply { writeInt32(1, 11) }.toByteArray()   // SENSOR_NIGHT_MODE
            val sensorLocation = ProtoWriter().apply { writeInt32(1, 1) }.toByteArray() // SENSOR_LOCATION
            val sensorParking = ProtoWriter().apply { writeInt32(1, 7) }.toByteArray()  // SENSOR_PARKING_BRAKE

            val sensorSvc = ProtoWriter().apply {
                writeMessage(1, sensorDriving)
                writeMessage(1, sensorNight)
                writeMessage(1, sensorLocation)
                writeMessage(1, sensorParking)
            }.toByteArray()

            val s2 = ProtoWriter().apply {
                writeInt32(1, 2) // Service ID 2
                writeMessage(2, sensorSvc) // field 2 = sensor_source_service
            }.toByteArray()

            // 3. Servicio 3: Video Sink Service (Canal 3: MediaCodec H.264 720p 60fps)
            val vc = ProtoWriter().apply {
                writeInt32(1, 2) // codec_resolution = 2 (1280x720)
                writeInt32(2, 1) // frame_rate = 1 (30/60 fps)
                writeInt32(3, 0) // width_margin = 0
                writeInt32(4, 0) // height_margin = 0
                writeInt32(5, dpi)
            }.toByteArray()

            val videoSink = ProtoWriter().apply {
                writeInt32(1, 3) // available_type = 3 (H264_BP)
                writeMessage(4, vc) // field 4 = video_configs
                writeInt32(7, 0) // field 7: display_type = 0 (DISPLAY_TYPE_MAIN)
            }.toByteArray()

            val s3 = ProtoWriter().apply {
                writeInt32(1, 3) // Service ID 3
                writeMessage(3, videoSink) // field 3 = media_sink_service
            }.toByteArray()

            // 4. Servicio 4: Media Source Service (Canal 4: Micrófono / Entrada de Voz)
            val audioCfg16k = ProtoWriter().apply {
                writeInt32(1, 16000) // sampling_rate = 16000 Hz
                writeInt32(2, 16)    // number_of_bits = 16
                writeInt32(3, 1)     // number_of_channels = 1
            }.toByteArray()

            val micSource = ProtoWriter().apply {
                writeInt32(1, 1) // available_type = 1 (MEDIA_CODEC_AUDIO_PCM)
                writeMessage(2, audioCfg16k) // field 2 = audio_config
            }.toByteArray()

            val s4 = ProtoWriter().apply {
                writeInt32(1, 4) // Service ID 4
                writeMessage(5, micSource) // field 5 = media_source_service
            }.toByteArray()

            // 5. Servicio 5: Media Sink Service - Media Audio (Canal 5: Música 48kHz Stereo)
            val audioCfg48kStereo = ProtoWriter().apply {
                writeInt32(1, 48000) // sampling_rate = 48000 Hz
                writeInt32(2, 16)    // number_of_bits = 16
                writeInt32(3, 2)     // number_of_channels = 2 (Stereo)
            }.toByteArray()

            val mediaAudioSink = ProtoWriter().apply {
                writeInt32(1, 1) // available_type = 1 (MEDIA_CODEC_AUDIO_PCM)
                writeInt32(2, 3) // audio_type = 3 (AUDIO_STREAM_MEDIA)
                writeMessage(3, audioCfg48kStereo) // field 3 = audio_configs
            }.toByteArray()

            val s5 = ProtoWriter().apply {
                writeInt32(1, 5) // Service ID 5
                writeMessage(3, mediaAudioSink) // field 3 = media_sink_service
            }.toByteArray()

            // 6. Servicio 6: Media Sink Service - System Audio (Canal 6: Audio del Sistema 16kHz Mono)
            val systemAudioSink = ProtoWriter().apply {
                writeInt32(1, 1) // available_type = 1 (MEDIA_CODEC_AUDIO_PCM)
                writeInt32(2, 1) // audio_type = 1 (AUDIO_STREAM_SYSTEM_AUDIO)
                writeMessage(3, audioCfg16k) // field 3 = audio_configs
            }.toByteArray()

            val s6 = ProtoWriter().apply {
                writeInt32(1, 6) // Service ID 6
                writeMessage(3, systemAudioSink) // field 3 = media_sink_service
            }.toByteArray()

            // 7. Servicio 7: Media Sink Service - Guidance Audio (Canal 7: Instrucciones de Navegación Waze 16kHz Mono)
            val guidanceAudioSink = ProtoWriter().apply {
                writeInt32(1, 1) // available_type = 1 (MEDIA_CODEC_AUDIO_PCM)
                writeInt32(2, 2) // audio_type = 2 (AUDIO_STREAM_GUIDANCE)
                writeMessage(3, audioCfg16k) // field 3 = audio_configs
            }.toByteArray()

            val s7 = ProtoWriter().apply {
                writeInt32(1, 7) // Service ID 7
                writeMessage(3, guidanceAudioSink) // field 3 = media_sink_service
            }.toByteArray()

            // 8. Servicio 9: Media Playback Status Service (Canal 9)
            val s9 = ProtoWriter().apply {
                writeInt32(1, 9) // Service ID 9
                writeMessage(9, ByteArray(0)) // field 9 = media_playback_service
            }.toByteArray()

            // HeadUnitInfo (Reconocido como Desktop Head Unit de Google)
            val hu = ProtoWriter().apply {
                writeString(1, "Google")
                writeString(2, "Desktop Head Unit")
                writeString(5, "Google")
                writeString(6, "Desktop Head Unit")
                writeString(7, "HUR")
                writeString(8, "1.1")
            }.toByteArray()

            // ServiceDiscoveryResponse con el conjunto vehicular completo y coincidencia exacta
            return ProtoWriter().apply {
                writeMessage(1, s1) // Service 1 (Input)
                writeMessage(1, s2) // Service 2 (Sensors)
                writeMessage(1, s3) // Service 3 (Video)
                writeMessage(1, s4) // Service 4 (Mic)
                writeMessage(1, s5) // Service 5 (Media Audio)
                writeMessage(1, s6) // Service 6 (System Audio)
                writeMessage(1, s7) // Service 7 (Guidance Audio)
                writeMessage(1, s9) // Service 9 (Playback Status)
                writeString(2, "Google") // make (coincide con auto aceptado en Gearhead)
                writeString(3, "Desktop Head Unit") // model
                writeInt32(6, 0)    // driver_position = LEFT
                writeString(7, "Google") // head_unit_make
                writeString(8, "Desktop Head Unit") // head_unit_model
                writeString(9, "HUR") // head_unit_software_build
                writeString(10, "1.1") // head_unit_software_version
                writeString(14, "Desktop Head Unit") // display_name
                writeMessage(17, hu) // HeadUnitInfo
            }.toByteArray()
        }

        /**
         * Construye el lote de sensores con estado de conducción no restringido y freno de mano activo.
         */
        fun buildDrivingStatusEvent(status: Int = 0): ByteArray {
            val drivingData = ProtoWriter().apply {
                writeInt32(1, status) // Status 0 = UNRESTRICTED
            }.toByteArray()
            return ProtoWriter().apply {
                writeMessage(13, drivingData) // field 13 = driving_status_data
            }.toByteArray()
        }

        /**
         * Construye el lote completo de sensores requerido por Android Auto para autorizar el streaming de video.
         * Incluye estado de conducción no restringido (0) y freno de mano enganchado (true).
         */
        fun buildSensorBatchEvent(drivingStatus: Int = 0, parkingBrake: Boolean = true): ByteArray {
            val drivingData = ProtoWriter().apply {
                writeInt32(1, drivingStatus) // Status 0 = UNRESTRICTED
            }.toByteArray()
            val pbData = ProtoWriter().apply {
                writeBool(1, parkingBrake) // true = ENGAGED
            }.toByteArray()
            return ProtoWriter().apply {
                writeMessage(7, pbData)       // field 7 = parking_brake_data
                writeMessage(13, drivingData) // field 13 = driving_status_data
            }.toByteArray()
        }

        /**
         * Constantes de control para canal 0.
         */
        const val MESSAGE_PING_REQUEST = 11
        const val MESSAGE_PING_RESPONSE = 12
        const val MESSAGE_NAV_FOCUS_REQUEST = 13
        const val MESSAGE_NAV_FOCUS_NOTIFICATION = 14
        const val MESSAGE_AUDIO_FOCUS_REQUEST = 18
        const val MESSAGE_AUDIO_FOCUS_NOTIFICATION = 19

        const val AUDIO_FOCUS_GAIN = 1
        const val AUDIO_FOCUS_GAIN_TRANSIENT = 2
        const val AUDIO_FOCUS_GAIN_TRANSIENT_MAY_DUCK = 3
        const val AUDIO_FOCUS_RELEASE = 4

        const val AUDIO_FOCUS_STATE_GAIN = 1
        const val AUDIO_FOCUS_STATE_GAIN_TRANSIENT = 2
        const val AUDIO_FOCUS_STATE_LOSS = 3
        const val AUDIO_FOCUS_STATE_LOSS_TRANSIENT_CAN_DUCK = 4
        const val AUDIO_FOCUS_STATE_LOSS_TRANSIENT = 5
        const val AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY = 6
        const val AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY = 7

        const val NAV_FOCUS_PROJECTED = 2

        /**
         * Parsea AudioFocusRequestNotification (Canal 0, msgId = 18).
         * Campo 1: request (enum AudioFocusRequestType)
         */
        fun parseAudioFocusRequest(payload: ByteArray): Int {
            if (payload.isEmpty()) return AUDIO_FOCUS_GAIN
            var i = 0
            while (i < payload.size) {
                val tag = payload[i++].toInt() and 0xFF
                val fieldNum = tag ushr 3
                val wireType = tag and 0x07
                if (wireType == 0) { // Varint
                    var value = 0
                    var shift = 0
                    while (i < payload.size) {
                        val b = payload[i++].toInt() and 0xFF
                        value = value or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    if (fieldNum == 1) return value
                } else if (wireType == 2) {
                    var len = 0
                    var shift = 0
                    while (i < payload.size) {
                        val b = payload[i++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    i += len
                } else {
                    break
                }
            }
            return AUDIO_FOCUS_GAIN
        }

        /**
         * Construye AudioFocusNotification (Canal 0, msgId = 19).
         * Campo 1: focus_state (enum AudioFocusStateType)
         */
        fun buildAudioFocusNotification(focusState: Int): ByteArray {
            return ProtoWriter().apply {
                writeInt32(1, focusState)
            }.toByteArray()
        }

        /**
         * Construye NavFocusNotification (Canal 0, msgId = 14).
         * Campo 1: focus_type (enum NavFocusType, 2 = NAV_FOCUS_PROJECTED)
         */
        fun buildNavFocusNotification(focusType: Int = NAV_FOCUS_PROJECTED): ByteArray {
            return ProtoWriter().apply {
                writeInt32(1, focusType)
            }.toByteArray()
        }

        /**
         * Respuesta de apertura de canal exitosa (status = 0 / STATUS_SUCCESS).
         */
        fun buildChannelOpenResponse(status: Int = 0): ByteArray {
            return byteArrayOf(0x08, status.toByte())
        }

        /**
         * Notificación para requerir inicio inmediato de video proyectado.
         * Mode: VIDEO_FOCUS_PROJECTED = 1
         * Unsolicited: true = 1
         */
        fun buildVideoFocusNotification(
            mode: Int = OaaProtocol.VIDEO_FOCUS_PROJECTED,
            unsolicited: Boolean = true
        ): ByteArray {
            return ProtoWriter().apply {
                writeInt32(1, mode)
                writeBool(2, unsolicited)
            }.toByteArray()
        }

        /**
         * Respuesta de configuración de Media Sink (Video o Audio).
         * status = 1 (STATUS_READY)
         * maxUnacked = 1
         * configurationIndices = [0] (selecciona el primer codec ofrecido en ServiceDiscovery)
         */
        fun buildMediaConfig(
            status: Int = 1,
            maxUnacked: Int = 1,
            configurationIndices: List<Int>? = listOf(0)
        ): ByteArray {
            return ProtoWriter().apply {
                writeInt32(1, status)
                writeInt32(2, maxUnacked)
                if (configurationIndices != null && configurationIndices.isNotEmpty()) {
                    writePackedVarints(3, configurationIndices)
                }
            }.toByteArray()
        }

        /**
         * Confirmación de recepción de frame multimedia (ACK).
         */
        fun buildMediaAck(
            sessionId: Int,
            ackCount: Int = 1
        ): ByteArray {
            return ProtoWriter().apply {
                writeInt32(1, sessionId)
                writeInt32(2, ackCount)
            }.toByteArray()
        }

        /**
         * Respuesta a la solicitud de mapeo de teclas (KeyBindingResponse).
         */
        fun buildKeyBindingResponse(status: Int = 0): ByteArray {
            return byteArrayOf(0x08, status.toByte())
        }

        /**
         * Respuesta a la solicitud de inicio de sensores (SensorResponse).
         */
        fun buildSensorResponse(status: Int = 0): ByteArray {
            return byteArrayOf(0x08, status.toByte())
        }

        /**
         * Extrae el sessionId numérico de un mensaje MEDIA_MESSAGE_START.
         */
        fun parseSessionId(payload: ByteArray): Int {
            if (payload.isEmpty()) return 0
            var i = 0
            while (i < payload.size) {
                val tag = payload[i++].toInt() and 0xFF
                val fieldNum = tag ushr 3
                val wireType = tag and 0x07
                if (wireType == 0) { // Varint
                    var value = 0
                    var shift = 0
                    while (i < payload.size) {
                        val b = payload[i++].toInt() and 0xFF
                        value = value or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    if (fieldNum == 1) return value
                } else if (wireType == 2) { // Length-delimited
                    var len = 0
                    var shift = 0
                    while (i < payload.size) {
                        val b = payload[i++].toInt() and 0xFF
                        len = len or ((b and 0x7F) shl shift)
                        if ((b and 0x80) == 0) break
                        shift += 7
                    }
                    i += len
                } else {
                    break
                }
            }
            return 0
        }

        /**
         * Construye un InputReport con TouchEvent para interacción táctil en milisegundos.
         */
        fun buildTouchInputReport(
            action: Int,
            x: Int,
            y: Int,
            timestampUs: Long = System.nanoTime() / 1000L
        ): ByteArray {
            // PointerData:
            // field 1: x
            // field 2: y
            // field 3: pointer_id = 0
            val pd = ProtoWriter().apply {
                writeInt32(1, x)
                writeInt32(2, y)
                writeInt32(3, 0)
            }.toByteArray()

            // TouchEvent:
            // field 1: pointer_data
            // field 2: action_index = 0
            // field 3: action (0=DOWN, 2=MOVE, 1=UP)
            val te = ProtoWriter().apply {
                writeMessage(1, pd)
                writeInt32(2, 0)
                writeInt32(3, action)
            }.toByteArray()

            // InputReport:
            // field 1: timestamp (microseconds)
            // field 3: touch_event
            return ProtoWriter().apply {
                writeInt64(1, timestampUs)
                writeMessage(3, te)
            }.toByteArray()
        }
    }
}
