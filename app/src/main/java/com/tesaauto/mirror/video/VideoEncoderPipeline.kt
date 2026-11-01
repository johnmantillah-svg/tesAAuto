package com.tesaauto.mirror.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class VideoEncoderPipeline(
    private val config: EncoderConfig = EncoderConfig(),
    private val onFrameEncoded: (ByteArray, Boolean) -> Unit
) {
    companion object {
        private const val TAG = "VideoEncoderPipeline"
        const val MIME_H264 = MediaFormat.MIMETYPE_VIDEO_AVC
        const val MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
    }

    data class EncoderConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val bitrate: Int = 3_500_000, // 3.5 Mbps
        val frameRate: Int = 60,
        val iFrameIntervalSeconds: Int = 2,
        val mimeType: String = MIME_H264
    )

    private var encoder: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    var isEncoding: Boolean = false
        private set

    // Caché de SPS/PPS (Codec Config) para entregar a clientes que se conecten tarde
    @Volatile
    var cachedSpsPps: ByteArray? = null
        private set

    /**
     * Inicializa y configura el MediaCodec en modo de entrada por Superficie (Surface Input).
     */
    fun start(): Surface {
        if (isEncoding && inputSurface != null) {
            return inputSurface!!
        }

        try {
            val format = MediaFormat.createVideoFormat(config.mimeType, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSeconds)

                // Modo de bitrate variable para optimizar ancho de banda en pantallas estáticas
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

                // Adjuntar cabeceras SPS/PPS a cada cuadro clave (IDR) para decodificación instantánea en navegadores
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)

                if (config.mimeType == MIME_H264) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }

            encoder = MediaCodec.createEncoderByType(config.mimeType).apply {
                setCallback(createCodecCallback())
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            isEncoding = true
            Log.i(TAG, "MediaCodec inicializado: ${config.mimeType} ${config.width}x${config.height} @ ${config.frameRate}fps (${config.bitrate / 1000} kbps)")
            return inputSurface!!
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando MediaCodec", e)
            stop()
            throw e
        }
    }

    /**
     * Callback asíncrono oficial de MediaCodec para emisión de NAL units con latencia mínima.
     */
    private fun createCodecCallback() = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // En modo COLOR_FormatSurface, el buffer de entrada es alimentado por la Surface del VirtualDisplay
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val outputBuffer: ByteBuffer? = codec.getOutputBuffer(index)
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)

                    val outData = ByteArray(info.size)
                    outputBuffer.get(outData)

                    val isConfigFrame = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    if (isConfigFrame) {
                        cachedSpsPps = outData
                        Log.d(TAG, "Configuración SPS/PPS capturada (${outData.size} bytes)")
                    }

                    onFrameEncoded(outData, isKeyFrame || isConfigFrame)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.w(TAG, "Error procesando buffer de salida de MediaCodec", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Excepción interna en MediaCodec: isRecoverable=${e.isRecoverable}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Formato de salida de video ajustado: $format")
        }
    }

    /**
     * Solicita en tiempo de ejecución la generación forzada de un cuadro clave (I-Frame/IDR).
     * Vital cuando un nuevo navegador de Tesla se conecta para sincronizar decodificación inmediata.
     */
    fun requestSyncFrame() {
        if (!isEncoding || encoder == null) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(params)
            Log.d(TAG, "I-Frame (IDR Sync) solicitado dinámicamente")
        } catch (e: Exception) {
            Log.w(TAG, "Fallo solicitando I-Frame", e)
        }
    }

    /**
     * Ajusta el bitrate en tiempo real (ej. ante saturación de buffer de red o alerta térmica).
     */
    fun updateBitrate(newBitrate: Int) {
        if (!isEncoding || encoder == null) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            }
            encoder?.setParameters(params)
            Log.i(TAG, "Bitrate dinámico actualizado a ${newBitrate / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Fallo actualizando bitrate dinámico", e)
        }
    }

    fun stop() {
        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo MediaCodec", e)
        }
        try {
            encoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando MediaCodec", e)
        }
        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando inputSurface", e)
        }
        encoder = null
        inputSurface = null
        isEncoding = false
        Log.i(TAG, "MediaCodec detenido y recursos liberados")
    }
}
