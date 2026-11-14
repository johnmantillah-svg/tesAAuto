package com.tesaauto.mirror.aa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Cliente Head Unit que se conecta a Android Auto (Gearhead) en 127.0.0.1:5277,
 * negocia el protocolo vehicular OAA, recibe y reensambla los cuadros H.264 para Tesla.
 */
class AndroidAutoHeadUnitClient(
    private val context: Context,
    private val host: String = "127.0.0.1",
    private val port: Int = 5277,
    var onVideoFrame: ((ByteArray) -> Unit)? = null,
    var onConnected: (() -> Unit)? = null,
    var onDisconnected: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AAHeadUnitClient"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
    }

    private var socket: Socket? = null
    private var dataInput: DataInputStream? = null
    private var dataOutput: DataOutputStream? = null
    private val sslHandler = OaaSslEngine(context)

    private val clientScope = CoroutineScope(Dispatchers.IO)
    private var workerJob: Job? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var isVideoActive: Boolean = false
        private set

    @Volatile
    var isVideoConfigured: Boolean = false
        private set

    @Volatile
    var isAllMediaConfigured: Boolean = false
        private set

    private val configuredAudioChannels = ConcurrentHashMap.newKeySet<Int>()
    private var currentVideoFocus: Int = OaaProtocol.VIDEO_FOCUS_NATIVE
    private var pendingVideoFocus: Boolean? = true
    private var mediaSetupTimeoutJob: Job? = null

    @Volatile
    var cachedSpsPps: ByteArray? = null
        private set
    private var videoFramesReceived = 0

    private var videoSessionId: Int = 0
    private val audioSessionIds = ConcurrentHashMap<Int, Int>()
    private val videoAssemblyBuffer = ByteArrayOutputStream(256 * 1024)

    fun start() {
        if (workerJob != null && workerJob?.isActive == true) return

        workerJob = clientScope.launch {
            runClientLoop()
        }
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        closeSocket("Detenido manualmente")
    }

    /**
     * Envía un evento táctil normalizado (0.0 a 1.0) directamente a Android Auto.
     */
    fun sendTouchEvent(action: String, normX: Double, normY: Double) {
        if (!isConnected || !sslHandler.isHandshakeCompleted) return

        val aaAction = when (action.lowercase()) {
            "down", "pointer_down", "touchstart" -> OaaProtocol.ACTION_DOWN
            "move", "pointer_move", "touchmove" -> OaaProtocol.ACTION_MOVED
            "up", "pointer_up", "touchend" -> OaaProtocol.ACTION_UP
            else -> OaaProtocol.ACTION_DOWN
        }

        val px = (normX.coerceIn(0.0, 1.0) * VIDEO_WIDTH).toInt()
        val py = (normY.coerceIn(0.0, 1.0) * VIDEO_HEIGHT).toInt()

        clientScope.launch {
            val inputReport = ProtoWriter.buildTouchInputReport(aaAction, px, py)
            sendEncryptedMessage(
                channel = OaaProtocol.CHANNEL_INPUT,
                msgId = OaaProtocol.INPUT_MESSAGE_INPUT_REPORT,
                payload = inputReport
            )
        }
    }

    private suspend fun runClientLoop() {
        while (clientScope.isActive) {
            try {
                Log.i(TAG, "Conectando al Servidor de Unidad Principal de Android Auto en $host:$port...")
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.soTimeout = 0 // Sin timeout arbitrario: keep-alive garantizado por ping/pong OAA
                sock.sendBufferSize = 1048576
                sock.receiveBufferSize = 1048576
                sock.connect(InetSocketAddress(host, port), 5000)

                socket = sock
                val inStream = BufferedInputStream(sock.getInputStream(), 65536)
                val outStream = BufferedOutputStream(sock.getOutputStream(), 65536)
                dataInput = DataInputStream(inStream)
                dataOutput = DataOutputStream(outStream)

                // 1. Enviar Car Hello (VersionRequest 1.6)
                Log.i(TAG, "Paso 1: Enviando Car Hello...")
                dataOutput?.write(OaaProtocol.CAR_HELLO_FRAME)
                dataOutput?.flush()

                // 2. Recibir Phone Hello (VersionResponse)
                val versionRespHeader = ByteArray(4)
                dataInput?.readFully(versionRespHeader)
                val versionRespLen = ((versionRespHeader[2].toInt() and 0xFF) shl 8) or (versionRespHeader[3].toInt() and 0xFF)
                val versionRespPayload = ByteArray(versionRespLen)
                dataInput?.readFully(versionRespPayload)
                Log.i(TAG, "Paso 2: Phone Hello recibido (${versionRespPayload.size} bytes). Versión confirmada!")

                // 3. Enviar Cleartext Auth Request (para arrancar modo TLS)
                Log.i(TAG, "Paso 3: Enviando Auth Request...")
                dataOutput?.write(OaaProtocol.AUTH_REQUEST_FRAME)
                dataOutput?.flush()

                // 4. Inicializar y ejecutar Handshake TLS 1.2
                if (!sslHandler.initialize()) {
                    throw IllegalStateException("Fallo inicializando keystore SSL para Android Auto")
                }
                Log.i(TAG, "Paso 4: Negociando Handshake TLS 1.2...")
                val tlsSuccess = sslHandler.doHandshake(inStream, outStream)
                if (!tlsSuccess) {
                    throw IllegalStateException("Handshake TLS no exitoso con Android Auto")
                }

                isConnected = true
                onConnected?.invoke()
                Log.i(TAG, "✅ Conexión TLS autenticada con Android Auto!")

                // 5. Enviar ServiceDiscoveryResponse anunciando video 720p y suite vehicular completa
                Log.i(TAG, "Paso 5: Enviando ServiceDiscoveryResponse...")
                val sdBytes = ProtoWriter.buildServiceDiscoveryResponse(VIDEO_WIDTH, VIDEO_HEIGHT, 120)
                sendEncryptedMessage(
                    channel = OaaProtocol.CHANNEL_CONTROL,
                    msgId = OaaProtocol.MSG_SERVICE_DISCOVERY_RESPONSE,
                    payload = sdBytes
                )

                // 6. Ciclo continuo de recepción de mensajes y streaming de video H.264
                readMessageLoop()

            } catch (e: Exception) {
                Log.w(TAG, "Conexión a Android Auto interrumpida (${e.javaClass.simpleName}): ${e.message}")
                closeSocket(e.message ?: e.javaClass.simpleName)
            }

            // Esperar antes de reconectar si el servicio sigue activo
            if (clientScope.isActive) {
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private fun readMessageLoop() {
        val header = ByteArray(4)

        while (isConnected && clientScope.isActive) {
            val input = dataInput ?: break

            // Leer cabecera OAA base de 4 bytes: [channel, flags, length (2 bytes BigEndian)]
            input.readFully(header)
            val channel = header[0].toInt() and 0xFF
            val flags = header[1].toInt() and 0xFF
            val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

            val fragmentType = flags and 0x03
            val isEncrypted = (flags and OaaProtocol.FLAG_ENCRYPTED) != 0

            // Si es FLAG_FIRST (1), OAA incluye 4 bytes adicionales para totalLength (Big-Endian Int32)
            if (fragmentType == OaaProtocol.FLAG_FIRST) {
                val extHeader = ByteArray(4)
                input.readFully(extHeader)
            }

            if (length <= 0) continue

            val packetData = ByteArray(length)
            input.readFully(packetData)

            // Desencriptar payload TLS si aplica
            val decrypted = if (isEncrypted) {
                sslHandler.unwrapData(packetData, 0, length)
            } else {
                packetData
            }

            if (decrypted.isEmpty()) continue

            // En FLAG_FIRST (1) y FLAG_BOTH (3), los primeros 2 bytes son el msgId
            val msgId: Int
            val payload: ByteArray
            if (fragmentType == OaaProtocol.FLAG_FIRST || fragmentType == OaaProtocol.FLAG_BOTH) {
                if (decrypted.size < 2) continue
                msgId = ((decrypted[0].toInt() and 0xFF) shl 8) or (decrypted[1].toInt() and 0xFF)
                payload = if (decrypted.size > 2) decrypted.copyOfRange(2, decrypted.size) else ByteArray(0)
            } else {
                // Fragmentos intermedios o finales de un mensaje multi-paquete: todo es contenido sin msgId
                msgId = -1
                payload = decrypted
            }

            handleIncomingOaaMessage(channel, flags, fragmentType, msgId, payload)
        }
    }

    private fun handleIncomingOaaMessage(
        channel: Int,
        flags: Int,
        fragmentType: Int,
        msgId: Int,
        payload: ByteArray
    ) {
        when (msgId) {
            OaaProtocol.MSG_CHANNEL_OPEN_REQUEST -> {
                Log.i(TAG, "Canal $channel: Petición de apertura de canal recibida. Enviando ChannelOpenResponse (control flag 0x0F)...")
                val resp = ProtoWriter.buildChannelOpenResponse(0)
                sendEncryptedMessage(
                    channel = channel,
                    msgId = OaaProtocol.MSG_CHANNEL_OPEN_RESPONSE,
                    payload = resp,
                    flags = OaaProtocol.FLAG_BOTH or OaaProtocol.FLAG_CONTROL or OaaProtocol.FLAG_ENCRYPTED
                )
                if (channel == OaaProtocol.CHANNEL_VIDEO) {
                    Log.i(TAG, "Canal Video (3) abierto exitosamente.")
                }
                if (channel == OaaProtocol.CHANNEL_SENSORS) {
                    Log.i(TAG, "Canal Sensores (2) abierto. Enviando DrivingStatus UNRESTRICTED inicial...")
                    sendEncryptedMessage(
                        channel = OaaProtocol.CHANNEL_SENSORS,
                        msgId = OaaProtocol.SENSOR_MESSAGE_BATCH,
                        payload = ProtoWriter.buildSensorBatchEvent(drivingStatus = 0, parkingBrake = true)
                    )
                }
                return
            }

            OaaProtocol.MSG_PING_REQUEST -> {
                sendEncryptedMessage(
                    channel = channel,
                    msgId = OaaProtocol.MSG_PING_RESPONSE,
                    payload = payload,
                    flags = OaaProtocol.FLAG_BOTH or OaaProtocol.FLAG_CONTROL or OaaProtocol.FLAG_ENCRYPTED
                )
                return
            }
        }

        when (channel) {
            OaaProtocol.CHANNEL_CONTROL -> {
                when (msgId) {
                    ProtoWriter.MESSAGE_AUDIO_FOCUS_REQUEST -> {
                        val reqType = ProtoWriter.parseAudioFocusRequest(payload)
                        Log.i(TAG, "Canal Control (0): AudioFocusRequest recibido (requestType=$reqType). Respondiendo...")
                        val focusState = if (reqType == ProtoWriter.AUDIO_FOCUS_RELEASE) {
                            ProtoWriter.AUDIO_FOCUS_STATE_LOSS
                        } else {
                            ProtoWriter.AUDIO_FOCUS_STATE_GAIN
                        }
                        val resp = ProtoWriter.buildAudioFocusNotification(focusState)
                        sendEncryptedMessage(
                            channel = OaaProtocol.CHANNEL_CONTROL,
                            msgId = ProtoWriter.MESSAGE_AUDIO_FOCUS_NOTIFICATION,
                            payload = resp
                        )
                        Log.i(TAG, "Canal Control (0): AudioFocusNotification enviado (focusState=$focusState)")
                    }

                    ProtoWriter.MESSAGE_NAV_FOCUS_REQUEST -> {
                        Log.i(TAG, "Canal Control (0): NavFocusRequest recibido. Respondiendo...")
                        val resp = ProtoWriter.buildNavFocusNotification(ProtoWriter.NAV_FOCUS_PROJECTED)
                        sendEncryptedMessage(
                            channel = OaaProtocol.CHANNEL_CONTROL,
                            msgId = ProtoWriter.MESSAGE_NAV_FOCUS_NOTIFICATION,
                            payload = resp
                        )
                        Log.i(TAG, "Canal Control (0): NavFocusNotification enviado (PROJECTED)")
                    }

                    ProtoWriter.MESSAGE_PING_REQUEST -> {
                        sendEncryptedMessage(
                            channel = OaaProtocol.CHANNEL_CONTROL,
                            msgId = ProtoWriter.MESSAGE_PING_RESPONSE,
                            payload = payload
                        )
                    }

                    else -> {
                        Log.i(TAG, "Canal Control (0): Mensaje recibido con msgId=$msgId (${payload.size} bytes)")
                    }
                }
            }

            OaaProtocol.CHANNEL_INPUT -> {
                // KeyBindingRequest (32770)
                if (msgId == OaaProtocol.INPUT_MESSAGE_KEY_BINDING_REQUEST) {
                    Log.i(TAG, "Canal Entrada (1): Solicitud de KeyBinding recibida. Respondiendo...")
                    sendEncryptedMessage(
                        channel = OaaProtocol.CHANNEL_INPUT,
                        msgId = OaaProtocol.INPUT_MESSAGE_KEY_BINDING_RESPONSE,
                        payload = ProtoWriter.buildKeyBindingResponse(0)
                    )
                } else {
                    Log.i(TAG, "Canal Entrada (1): Mensaje recibido con msgId=$msgId (${payload.size} bytes)")
                }
            }

            OaaProtocol.CHANNEL_SENSORS -> {
                // SensorStartRequest (32769)
                if (msgId == OaaProtocol.SENSOR_MESSAGE_REQUEST) {
                    Log.i(TAG, "Canal Sensores (2): Solicitud de sensores recibida. Enviando SensorResponse y DrivingStatus...")
                    // 1. Enviar SensorResponse(status = 0)
                    sendEncryptedMessage(
                        channel = OaaProtocol.CHANNEL_SENSORS,
                        msgId = OaaProtocol.SENSOR_MESSAGE_RESPONSE,
                        payload = ProtoWriter.buildSensorResponse(0)
                    )
                    // 2. Enviar SensorBatch(status = UNRESTRICTED, parkingBrake = true)
                    sendEncryptedMessage(
                        channel = OaaProtocol.CHANNEL_SENSORS,
                        msgId = OaaProtocol.SENSOR_MESSAGE_BATCH,
                        payload = ProtoWriter.buildSensorBatchEvent(drivingStatus = 0, parkingBrake = true)
                    )
                } else {
                    Log.i(TAG, "Canal Sensores (2): Mensaje recibido con msgId=$msgId (${payload.size} bytes)")
                }
            }

            OaaProtocol.CHANNEL_VIDEO -> {
                when (msgId) {
                    OaaProtocol.MEDIA_MESSAGE_SETUP -> {
                        Log.i(TAG, "Canal Video (3): MEDIA_MESSAGE_SETUP recibido. Configurando canal con MEDIA_MESSAGE_CONFIG...")
                        val config = ProtoWriter.buildMediaConfig(
                            status = 1, // STATUS_READY (1 = READY, 2 = WAIT)
                            maxUnacked = 1,
                            configurationIndices = listOf(0)
                        )
                        sendEncryptedMessage(
                            channel = OaaProtocol.CHANNEL_VIDEO,
                            msgId = OaaProtocol.MEDIA_MESSAGE_CONFIG,
                            payload = config
                        )
                        isVideoConfigured = true
                        checkMediaConfigurationComplete()

                        // Temporizador de seguridad: si los canales de audio demoran o no envían SETUP,
                        // habilitar la proyección tras 800ms
                        mediaSetupTimeoutJob?.cancel()
                        mediaSetupTimeoutJob = clientScope.launch {
                            kotlinx.coroutines.delay(800)
                            if (isConnected && isVideoConfigured && !isAllMediaConfigured) {
                                Log.i(TAG, "Temporizador multimedia: habilitando video focus para streaming vehicular...")
                                isAllMediaConfigured = true
                                requestVideoFocus(pendingVideoFocus ?: true)
                            }
                        }
                    }

                    OaaProtocol.MEDIA_MESSAGE_START -> {
                        videoSessionId = ProtoWriter.parseSessionId(payload)
                        isVideoActive = true
                        Log.i(TAG, "Canal Video (3): Stream de Video iniciado por Android Auto! sessionId=$videoSessionId")
                    }

                    OaaProtocol.MEDIA_MESSAGE_STOP -> {
                        Log.i(TAG, "Canal Video (3): Stream de Video detenido por Android Auto.")
                        isVideoActive = false
                    }

                    OaaProtocol.MEDIA_MESSAGE_VIDEO_FOCUS_REQUEST -> {
                        Log.i(TAG, "Canal Video (3): VideoFocusRequest recibido de Android Auto. Respondiendo con VideoFocusNotification...")
                        currentVideoFocus = OaaProtocol.VIDEO_FOCUS_PROJECTED
                        val focusNotification = ProtoWriter.buildVideoFocusNotification(
                            mode = OaaProtocol.VIDEO_FOCUS_PROJECTED,
                            unsolicited = false
                        )
                        sendEncryptedMessage(
                            channel = OaaProtocol.CHANNEL_VIDEO,
                            msgId = OaaProtocol.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION,
                            payload = focusNotification
                        )
                    }

                    OaaProtocol.MEDIA_MESSAGE_CODEC_CONFIG -> {
                        // El codec config contiene SPS/PPS en formato Annex B
                        if (payload.isNotEmpty()) {
                            val spsPps = sanitizeH264Nal(payload)
                            cachedSpsPps = spsPps
                            Log.i(TAG, "Canal Video (3): SPS/PPS recibido (${spsPps.size} bytes). Emitiendo a Tesla WebCodecs!")
                            onVideoFrame?.invoke(spsPps)
                        }
                    }

                    OaaProtocol.MEDIA_MESSAGE_DATA -> {
                        // Frame de video H.264 (primeros 8 bytes son timestamp uint64 BigEndian)
                        if (fragmentType == OaaProtocol.FLAG_BOTH) {
                            if (payload.size > 8) {
                                val rawNal = payload.copyOfRange(8, payload.size)
                                val nal = sanitizeH264Nal(rawNal)
                                processAndEmitVideoFrame(nal)
                                sendVideoAck()
                            }
                        } else if (fragmentType == OaaProtocol.FLAG_FIRST) {
                            // Primer fragmento de un cuadro H.264 grande
                            videoAssemblyBuffer.reset()
                            if (payload.size > 8) {
                                videoAssemblyBuffer.write(payload, 8, payload.size - 8)
                            }
                        }
                    }

                    -1 -> {
                        // Fragmentos subsecuentes (sin msgId)
                        if (fragmentType == 0) { // Middle fragment
                            videoAssemblyBuffer.write(payload)
                        } else if (fragmentType == OaaProtocol.FLAG_LAST) { // Last fragment
                            videoAssemblyBuffer.write(payload)
                            val assembled = videoAssemblyBuffer.toByteArray()
                            val nal = sanitizeH264Nal(assembled)
                            processAndEmitVideoFrame(nal)
                            sendVideoAck()
                        }
                    }

                    else -> {
                        Log.i(TAG, "Canal Video (3): Mensaje recibido con msgId=$msgId (${payload.size} bytes)")
                    }
                }
            }

            OaaProtocol.CHANNEL_MIC,
            OaaProtocol.CHANNEL_AUDIO_MEDIA,
            OaaProtocol.CHANNEL_AUDIO_SYSTEM,
            OaaProtocol.CHANNEL_AUDIO_GUIDANCE -> {
                when (msgId) {
                    OaaProtocol.MEDIA_MESSAGE_SETUP -> {
                        Log.i(TAG, "Canal Audio ($channel): MEDIA_MESSAGE_SETUP recibido. Configurando...")
                        val config = ProtoWriter.buildMediaConfig(
                            status = 1, // STATUS_READY (1 = READY, 2 = WAIT)
                            maxUnacked = 1,
                            configurationIndices = listOf(0)
                        )
                        sendEncryptedMessage(
                            channel = channel,
                            msgId = OaaProtocol.MEDIA_MESSAGE_CONFIG,
                            payload = config
                        )
                        configuredAudioChannels.add(channel)
                        checkMediaConfigurationComplete()
                    }

                    OaaProtocol.MEDIA_MESSAGE_START -> {
                        val sId = ProtoWriter.parseSessionId(payload)
                        audioSessionIds[channel] = sId
                        Log.i(TAG, "Canal Audio ($channel): Stream iniciado con sessionId=$sId")
                    }

                    OaaProtocol.MEDIA_MESSAGE_DATA -> {
                        // Confirmar recepción de paquete de audio para mantener el pipeline fluyendo
                        val sId = audioSessionIds[channel] ?: 0
                        val ack = ProtoWriter.buildMediaAck(sId, 1)
                        sendEncryptedMessage(
                            channel = channel,
                            msgId = OaaProtocol.MEDIA_MESSAGE_ACK,
                            payload = ack
                        )
                    }
                }
            }

            else -> {
                Log.i(TAG, "Mensaje recibido en canal $channel: msgId=$msgId (${payload.size} bytes)")
            }
        }
    }

    private fun sendVideoAck() {
        val ack = ProtoWriter.buildMediaAck(videoSessionId, 1)
        sendEncryptedMessage(
            channel = OaaProtocol.CHANNEL_VIDEO,
            msgId = OaaProtocol.MEDIA_MESSAGE_ACK,
            payload = ack
        )
    }

    private fun sendEncryptedMessage(
        channel: Int,
        msgId: Int,
        payload: ByteArray,
        flags: Int = OaaProtocol.FLAG_BOTH or OaaProtocol.FLAG_ENCRYPTED
    ) {
        try {
            val enc = sslHandler.wrapData(channel, flags, msgId, payload)
            synchronized(this) {
                dataOutput?.write(enc)
                dataOutput?.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enviando mensaje msgId=$msgId en canal $channel: ${e.message}")
        }
    }

    private fun checkMediaConfigurationComplete() {
        if (!isVideoConfigured) return
        val requiredAudio = setOf(
            OaaProtocol.CHANNEL_AUDIO_MEDIA,
            OaaProtocol.CHANNEL_AUDIO_SYSTEM,
            OaaProtocol.CHANNEL_AUDIO_GUIDANCE
        )
        if (configuredAudioChannels.containsAll(requiredAudio)) {
            if (!isAllMediaConfigured) {
                isAllMediaConfigured = true
                mediaSetupTimeoutJob?.cancel()
                mediaSetupTimeoutJob = null
                Log.i(TAG, "✅ Todos los canales multimedia (Video 3, Audio 5, 6, 7) configurados exitosamente!")
                clientScope.launch {
                    kotlinx.coroutines.delay(300) // Breve estabilización idéntica a Taada
                    if (isConnected && isAllMediaConfigured) {
                        requestVideoFocus(pendingVideoFocus ?: true)
                    }
                }
            }
        }
    }

    private fun processAndEmitVideoFrame(nal: ByteArray) {
        val frameToSend = if (isIdrFrame(nal) && cachedSpsPps != null) {
            val sps = cachedSpsPps!!
            val combined = ByteArray(sps.size + nal.size)
            System.arraycopy(sps, 0, combined, 0, sps.size)
            System.arraycopy(nal, 0, combined, sps.size, nal.size)
            combined
        } else {
            nal
        }

        if (++videoFramesReceived % 60 == 0) {
            Log.i(TAG, "Video frame vehicular #$videoFramesReceived emitido (${frameToSend.size} bytes, isIdr=${isIdrFrame(nal)})")
        }
        onVideoFrame?.invoke(frameToSend)
    }

    private fun isIdrFrame(nal: ByteArray): Boolean {
        for (i in 0 until minOf(nal.size - 4, 32)) {
            if (nal[i] == 0.toByte() && nal[i + 1] == 0.toByte()) {
                val nalByte = if (nal[i + 2] == 1.toByte()) {
                    nal[i + 3].toInt() and 0xFF
                } else if (nal[i + 2] == 0.toByte() && nal[i + 3] == 1.toByte() && i + 4 < nal.size) {
                    nal[i + 4].toInt() and 0xFF
                } else {
                    0
                }
                val nalType = nalByte and 0x1F
                if (nalType == 5) return true
            }
        }
        return false
    }

    private fun sanitizeH264Nal(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        // Annex B start code de 4 bytes: 00 00 00 01
        if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        // Annex B start code de 3 bytes: 00 00 01 -> anteponer 00 para estandarizar a 4 bytes
        if (data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            val out = ByteArray(data.size + 1)
            out[0] = 0
            System.arraycopy(data, 0, out, 1, data.size)
            return out
        }
        // Si falta prefijo de 2 ceros (ej. 00 01 xx): anteponer 00 00
        if (data[0] == 0.toByte() && data[1] == 1.toByte()) {
            val out = ByteArray(data.size + 2)
            out[0] = 0
            out[1] = 0
            System.arraycopy(data, 0, out, 2, data.size)
            return out
        }
        return data
    }

    /**
     * Solicita o reafirma el enfoque de video vehicular (VideoFocusNotification) a Android Auto.
     * En mode = VIDEO_FOCUS_PROJECTED (1), Android Auto activa la interfaz vehicular proyectada (Waze / Maps).
     */
    fun requestVideoFocus(projected: Boolean = true, force: Boolean = false) {
        if (!isConnected || !isAllMediaConfigured) {
            Log.i(TAG, "requestVideoFocus($projected) en espera de configuración multimedia (isAllMediaConfigured=$isAllMediaConfigured)")
            pendingVideoFocus = projected
            return
        }
        val mode = if (projected) OaaProtocol.VIDEO_FOCUS_PROJECTED else OaaProtocol.VIDEO_FOCUS_NATIVE
        if (!force && currentVideoFocus == mode) {
            Log.d(TAG, "requestVideoFocus($projected) omitido: ya en modo $mode")
            return
        }
        currentVideoFocus = mode
        Log.i(TAG, "Canal Video (3): Asertando VideoFocusNotification (mode=$mode, unsolicited=true, force=$force)...")
        val focusNotification = ProtoWriter.buildVideoFocusNotification(
            mode = mode,
            unsolicited = true
        )
        sendEncryptedMessage(
            channel = OaaProtocol.CHANNEL_VIDEO,
            msgId = OaaProtocol.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION,
            payload = focusNotification
        )
    }

    private fun closeSocket(reason: String) {
        mediaSetupTimeoutJob?.cancel()
        mediaSetupTimeoutJob = null
        isVideoConfigured = false
        isAllMediaConfigured = false
        configuredAudioChannels.clear()
        currentVideoFocus = OaaProtocol.VIDEO_FOCUS_NATIVE
        pendingVideoFocus = true
        isConnected = false
        isVideoActive = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        dataInput = null
        dataOutput = null
        onDisconnected?.invoke(reason)
    }
}
