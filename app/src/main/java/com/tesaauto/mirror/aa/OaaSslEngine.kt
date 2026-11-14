package com.tesaauto.mirror.aa

import android.content.Context
import android.util.Log
import com.tesaauto.mirror.R
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Gestiona la negociación TLS 1.2 sobre el socket de Android Auto (OAA) utilizando el certificado de Google Keystore.
 */
class OaaSslEngine(private val context: Context) {

    companion object {
        private const val TAG = "OaaSslEngine"
        private const val BUFFER_SIZE = 256 * 1024
    }

    private var sslEngine: SSLEngine? = null
    private val myNetData: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private val peerNetData: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private val peerAppData: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)

    var isHandshakeCompleted: Boolean = false
        private set

    fun initialize(): Boolean {
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            val isStream: InputStream = context.resources.openRawResource(R.raw.keystore)
            isStream.use { input ->
                ks.load(input, "aa".toCharArray())
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, "aa".toCharArray())

            // TrustAll manager para aceptar el certificado del servidor local de Android Auto
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(kmf.keyManagers, trustAll, SecureRandom())

            val engine = sslContext.createSSLEngine()
            engine.useClientMode = true
            engine.needClientAuth = true
            engine.enabledProtocols = arrayOf("TLSv1.2")
            sslEngine = engine

            // Inicializar buffers limpios
            peerNetData.clear()
            peerNetData.flip()

            Log.i(TAG, "SSLEngine TLSv1.2 inicializado con éxito.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando SSLEngine: ${e.message}", e)
            false
        }
    }

    /**
     * Ejecuta el ciclo de Handshake TLS coordinado con los mensajes de OAA.
     */
    fun doHandshake(
        inputStream: InputStream,
        outputStream: OutputStream
    ): Boolean {
        val engine = sslEngine ?: return false
        engine.beginHandshake()

        var handshakeStatus = engine.handshakeStatus
        val emptyApp = ByteBuffer.allocate(0)

        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
            handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    myNetData.clear()
                    val result = engine.wrap(emptyApp, myNetData)
                    handshakeStatus = result.handshakeStatus
                    myNetData.flip()

                    val bytesProduced = myNetData.remaining()
                    if (bytesProduced > 0) {
                        // Empaquetado OAA Frame: Channel 0, Flags 3, Length = (bytesProduced + 2), MsgId = 3 (ENCAPSULATED_SSL)
                        val totalLen = bytesProduced + 2
                        val frame = ByteArray(bytesProduced + 6)
                        frame[0] = 0.toByte() // Channel 0
                        frame[1] = 3.toByte() // Flags 3
                        frame[2] = ((totalLen shr 8) and 0xFF).toByte()
                        frame[3] = (totalLen and 0xFF).toByte()
                        frame[4] = 0.toByte() // MsgId high = 0
                        frame[5] = 3.toByte() // MsgId low = 3
                        myNetData.get(frame, 6, bytesProduced)

                        outputStream.write(frame)
                        outputStream.flush()
                        Log.d(TAG, "Handshake NEED_WRAP enviado: $bytesProduced bytes TLS (Frame ${frame.size} bytes)")
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // Si el buffer de red está vacío o se consumió por completo, leemos la siguiente trama OAA del socket
                    if (!peerNetData.hasRemaining()) {
                        val header = ByteArray(4)
                        readFully(inputStream, header)
                        val flags = header[1].toInt() and 0xFF
                        val len = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

                        if (len <= 0) continue

                        val payload = ByteArray(len)
                        readFully(inputStream, payload)

                        val tlsOffset = when {
                            payload.size >= 3 && (payload[0].toInt() in 0x14..0x17) && payload[1] == 0x03.toByte() -> 0
                            payload.size >= 5 && (payload[2].toInt() in 0x14..0x17) && payload[3] == 0x03.toByte() -> 2
                            payload.size >= 7 && (payload[4].toInt() in 0x14..0x17) && payload[5] == 0x03.toByte() -> 4
                            else -> if (len > 2) 2 else 0
                        }

                        val tlsRecordLen = len - tlsOffset
                        peerNetData.clear()
                        if (tlsRecordLen > 0) {
                            peerNetData.put(payload, tlsOffset, tlsRecordLen)
                        }
                        peerNetData.flip()

                        Log.d(TAG, "Handshake NEED_UNWRAP frame recibido: flags=0x${Integer.toHexString(flags)}, len=$len, tlsOffset=$tlsOffset, tlsLen=$tlsRecordLen, firstBytes=${payload.take(8).joinToString(" ") { "%02x".format(it) }}")
                    }

                    peerAppData.clear()
                    val result = engine.unwrap(peerNetData, peerAppData)
                    handshakeStatus = result.handshakeStatus
                    Log.d(TAG, "Handshake NEED_UNWRAP: status=${result.status}, consumidos=${result.bytesConsumed()}, restantes=${peerNetData.remaining()} -> HandshakeStatus=$handshakeStatus")

                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        peerNetData.compact()
                        val header = ByteArray(4)
                        readFully(inputStream, header)
                        val flags = header[1].toInt() and 0xFF
                        val len = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
                        if (len > 0) {
                            val payload = ByteArray(len)
                            readFully(inputStream, payload)
                            val tlsOffset = when {
                                payload.size >= 3 && (payload[0].toInt() in 0x14..0x17) && payload[1] == 0x03.toByte() -> 0
                                payload.size >= 5 && (payload[2].toInt() in 0x14..0x17) && payload[3] == 0x03.toByte() -> 2
                                payload.size >= 7 && (payload[4].toInt() in 0x14..0x17) && payload[5] == 0x03.toByte() -> 4
                                else -> 0 // En fragmentos subsecuentes no hay cabecera de mensaje
                            }
                            val tlsRecordLen = len - tlsOffset
                            if (tlsRecordLen > 0) {
                                peerNetData.put(payload, tlsOffset, tlsRecordLen)
                            }
                        }
                        peerNetData.flip()
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task: Runnable?
                    while (engine.delegatedTask.also { task = it } != null) {
                        task?.run()
                    }
                    handshakeStatus = engine.handshakeStatus
                }

                else -> break
            }
        }

        isHandshakeCompleted = true
        Log.i(TAG, "✅ TLS 1.2 Handshake COMPLETADO CON ÉXITO con Android Auto!")
        return true
    }

    /**
     * Encripta un mensaje de aplicación (con cabecera OAA) para enviarlo sobre el socket TLS.
     */
    @Synchronized
    fun wrapData(
        channel: Int,
        flags: Int,
        msgId: Int,
        payload: ByteArray
    ): ByteArray {
        val engine = sslEngine ?: return ByteArray(0)

        // El payload plano a cifrar contiene [MsgId (2 bytes BigEndian)] + [Payload]
        val plainMsg = ByteBuffer.allocate(payload.size + 2)
        plainMsg.put(((msgId shr 8) and 0xFF).toByte())
        plainMsg.put((msgId and 0xFF).toByte())
        plainMsg.put(payload)
        plainMsg.flip()

        myNetData.clear()
        engine.wrap(plainMsg, myNetData)
        myNetData.flip()

        val tlsLen = myNetData.remaining()
        // Empaquetar en trama OAA: [Channel: 1 byte] + [Flags | FLAG_ENCRYPTED: 1 byte] + [Length: 2 bytes BigEndian] + [TLS record]
        val frame = ByteArray(4 + tlsLen)
        frame[0] = channel.toByte()
        frame[1] = (flags or OaaProtocol.FLAG_ENCRYPTED).toByte()
        frame[2] = ((tlsLen shr 8) and 0xFF).toByte()
        frame[3] = (tlsLen and 0xFF).toByte()
        myNetData.get(frame, 4, tlsLen)

        return frame
    }

    /**
     * Desencripta un registro TLS recibido del socket.
     */
    @Synchronized
    fun unwrapData(
        encryptedBytes: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        val engine = sslEngine ?: return ByteArray(0)

        if (length > peerNetData.capacity()) {
            Log.w(TAG, "Registro TLS demasiado grande ($length bytes > ${peerNetData.capacity()})")
            return ByteArray(0)
        }

        // Mantener bytes parciales si hubo BUFFER_UNDERFLOW previo
        peerNetData.compact()
        peerNetData.put(encryptedBytes, offset, length)
        peerNetData.flip()

        val output = java.io.ByteArrayOutputStream(length)
        while (peerNetData.hasRemaining()) {
            peerAppData.clear()
            val result = engine.unwrap(peerNetData, peerAppData)
            peerAppData.flip()

            if (peerAppData.hasRemaining()) {
                val chunk = ByteArray(peerAppData.remaining())
                peerAppData.get(chunk)
                output.write(chunk)
            }

            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW ||
                result.status == SSLEngineResult.Status.CLOSED
            ) {
                break
            }
        }
        return output.toByteArray()
    }

    private fun readFully(inputStream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inputStream.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IllegalStateException("Fin inesperado de stream leyendo datos")
            offset += read
        }
    }
}
