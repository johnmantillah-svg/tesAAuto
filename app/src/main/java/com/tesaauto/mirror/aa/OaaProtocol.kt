package com.tesaauto.mirror.aa

/**
 * Constantes y definiciones del protocolo Open Automotive Alliance (OAA / Android Auto).
 */
object OaaProtocol {
    // Canales
    const val CHANNEL_CONTROL = 0
    const val CHANNEL_INPUT = 1
    const val CHANNEL_SENSORS = 2
    const val CHANNEL_VIDEO = 3
    const val CHANNEL_MIC = 4
    const val CHANNEL_AUDIO_MEDIA = 5
    const val CHANNEL_AUDIO_SYSTEM = 6
    const val CHANNEL_AUDIO_GUIDANCE = 7
    const val CHANNEL_PLAYBACK_STATUS = 9

    // Banderas de trama OAA
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02
    const val FLAG_BOTH = 0x03
    const val FLAG_CONTROL = 0x04
    const val FLAG_ENCRYPTED = 0x08

    // Mensajes de Control (Canal 0)
    const val MSG_VERSION_REQUEST = 1
    const val MSG_VERSION_RESPONSE = 2
    const val MSG_ENCAPSULATED_SSL = 3
    const val MSG_AUTH_COMPLETE = 4
    const val MSG_SERVICE_DISCOVERY_REQUEST = 5
    const val MSG_SERVICE_DISCOVERY_RESPONSE = 6
    const val MSG_CHANNEL_OPEN_REQUEST = 7
    const val MSG_CHANNEL_OPEN_RESPONSE = 8
    const val MSG_CHANNEL_CLOSE_NOTIFICATION = 9
    const val MSG_PING_REQUEST = 11
    const val MSG_PING_RESPONSE = 12

    // Mensajes de Media / Video / Audio (Canales 3, 4, 5, 6, 7)
    // Constantes verificadas 100% contra el enum oficial MediaMessageId en el bytecode
    const val MEDIA_MESSAGE_DATA = 0
    const val MEDIA_MESSAGE_CODEC_CONFIG = 1
    const val MEDIA_MESSAGE_SETUP = 32768                        // 0x8000
    const val MEDIA_MESSAGE_START = 32769                        // 0x8001
    const val MEDIA_MESSAGE_STOP = 32770                         // 0x8002
    const val MEDIA_MESSAGE_CONFIG = 32771                       // 0x8003
    const val MEDIA_MESSAGE_ACK = 32772                          // 0x8004
    const val MEDIA_MESSAGE_MICROPHONE_REQUEST = 32773           // 0x8005
    const val MEDIA_MESSAGE_MICROPHONE_RESPONSE = 32774          // 0x8006
    const val MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST = 32775          // 0x8007
    const val MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION = 32776     // 0x8008
    const val MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST = 32777     // 0x8009
    const val MEDIA_MESSAGE_UPDATE_UI_CONFIG_REPLY = 32778       // 0x800A
    const val MEDIA_MESSAGE_AUDIO_UNDERFLOW_NOTIFICATION = 32779 // 0x800B

    // Mensajes de Entrada / Touch (Canal 1)
    const val INPUT_MESSAGE_INPUT_REPORT = 32769
    const val INPUT_MESSAGE_KEY_BINDING_REQUEST = 32770
    const val INPUT_MESSAGE_KEY_BINDING_RESPONSE = 32771
    const val INPUT_MESSAGE_INPUT_FEEDBACK = 32772

    // Mensajes de Sensores (Canal 2)
    const val SENSOR_MESSAGE_REQUEST = 32769
    const val SENSOR_MESSAGE_RESPONSE = 32770
    const val SENSOR_MESSAGE_BATCH = 32771
    const val SENSOR_MESSAGE_ERROR = 32772

    // Acciones táctiles
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVED = 2

    // Modos de enfoque de video
    const val VIDEO_FOCUS_PROJECTED = 1
    const val VIDEO_FOCUS_NATIVE = 2

    // Tramas preensambladas del Handshake inicial (en texto claro)
    // Car Hello: Channel 0, Flags 3, Length 6, MsgId 1, Major 1, Minor 6
    val CAR_HELLO_FRAME = byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 6)

    // Auth Request: Channel 0, Flags 3, Length 4, MsgId 4, Payload 08 00
    val AUTH_REQUEST_FRAME = byteArrayOf(0, 3, 0, 4, 0, 4, 8, 0)
}
