package com.tesaauto.mirror.server

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.netty.Netty
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet

class TeslaEmbeddedServer(
    private val context: Context,
    private val host: String = "0.0.0.0",
    val port: Int = 8080,
    val sslPort: Int = 8443,
    private val onClientConnected: (() -> Unit)? = null,
    private val onTouchEventReceived: ((String, Double, Double, Long) -> Unit)? = null
) {
    companion object {
        private const val TAG = "TeslaEmbeddedServer"
    }

    var onLaunchAppRequest: ((String) -> Boolean)? = null
    var onGetStatusRequest: (() -> JSONObject)? = null
    var onTapRequest: ((Double, Double) -> Unit)? = null
    var onSwipeRequest: ((Double, Double, Double, Double, Int) -> Unit)? = null
    var onKeyRequest: ((Int) -> Unit)? = null
    var onHomeRequest: (() -> Unit)? = null
    var getInitialConfigFrame: (() -> ByteArray?)? = null

    private var serverEngine: ApplicationEngine? = null

    private class ConnectedClient(
        val session: WebSocketSession,
        val frameChannel: Channel<ByteArray> = Channel(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST),
        val sendMutex: Mutex = Mutex()
    ) {
        suspend fun sendFrame(frame: Frame) {
            sendMutex.withLock {
                session.send(frame)
            }
        }
    }

    private val activeClients = CopyOnWriteArraySet<ConnectedClient>()
    private val serverScope = CoroutineScope(Dispatchers.IO)
    private var lastLaunchTime = 0L
    private var lastLaunchPkg = ""

    private fun safeLaunchApp(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (pkg == lastLaunchPkg && (now - lastLaunchTime) < 1500) {
            return true // Ignorar llamada duplicada en menos de 1.5s
        }
        lastLaunchTime = now
        lastLaunchPkg = pkg
        return onLaunchAppRequest?.invoke(pkg) ?: false
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isTlsActive: Boolean = false
        private set

    private fun loadKeyStore(): Pair<KeyStore, String>? {
        // 1. Prioridad: Certificado oficial Let's Encrypt (CN=taada.top)
        try {
            val ks = KeyStore.getInstance("PKCS12")
            context.assets.open("keystore/server_pbe.p12").use { input ->
                ks.load(input, "aa".toCharArray())
            }
            if (ks.containsAlias("tesla")) {
                Log.i(TAG, "✅ Keystore TLS oficial Let's Encrypt cargado (alias: tesla, CN=taada.top)")
                return Pair(ks, "tesla")
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cargar server_pbe.p12: ${e.message}")
        }

        // 2. Fallback
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            context.assets.open("keystore/tesaauto.p12").use { input ->
                ks.load(input, "tesaautopassword".toCharArray())
            }
            Log.i(TAG, "Keystore TLS fallback cargado (alias: tesaauto)")
            Pair(ks, "tesaauto")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cargar keystore fallback: ${e.message}. El servidor solo operará en HTTP.")
            null
        }
    }

    fun start() {
        if (isRunning) return

        val keyStorePair = loadKeyStore()

        val environment = applicationEngineEnvironment {
            connector {
                this.port = this@TeslaEmbeddedServer.port
                this.host = this@TeslaEmbeddedServer.host
            }

            if (keyStorePair != null) {
                val (keyStore, keyAlias) = keyStorePair
                val pwd = if (keyAlias == "tesla") "aa".toCharArray() else "tesaautopassword".toCharArray()
                try {
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = keyAlias,
                        keyStorePassword = { pwd },
                        privateKeyPassword = { pwd }
                    ) {
                        this.port = this@TeslaEmbeddedServer.sslPort
                        this.host = this@TeslaEmbeddedServer.host
                    }

                    // También abrir en puerto 8081 para compatibilidad total con Taada
                    if (this@TeslaEmbeddedServer.sslPort != 8081) {
                        sslConnector(
                            keyStore = keyStore,
                            keyAlias = keyAlias,
                            keyStorePassword = { pwd },
                            privateKeyPassword = { pwd }
                        ) {
                            this.port = 8081
                            this.host = this@TeslaEmbeddedServer.host
                        }
                    }

                    isTlsActive = true
                    Log.i(TAG, "✅ Conectores SSL HTTPS habilitados con éxito en puertos ${this@TeslaEmbeddedServer.sslPort} y 8081 (Let's Encrypt)")
                } catch (e: Exception) {
                    Log.e(TAG, "Fallo configurando conector SSL HTTPS: ${e.message}", e)
                    isTlsActive = false
                }
            } else {
                isTlsActive = false
            }

            module {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(30)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }

                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Authorization)
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                }

                routing {
                    // Servir cliente web principal para el navegador Chromium de Tesla
                    get("/") {
                        val htmlContent = loadAssetFile("web/index.html")
                        if (htmlContent != null) {
                            call.respondBytes(htmlContent, ContentType.Text.Html)
                        } else {
                            call.respondText(
                                "<!DOCTYPE html><html><body><h2>TesAAuto Server Active</h2><p>Assets no encontrados.</p></body></html>",
                                ContentType.Text.Html
                            )
                        }
                    }

                    get("/jmuxer.min.js") {
                        val jsContent = loadAssetFile("web/jmuxer.min.js")
                        if (jsContent != null) {
                            call.respondBytes(jsContent, ContentType.Application.JavaScript)
                        } else {
                            call.respondText("Not Found", status = io.ktor.http.HttpStatusCode.NotFound)
                        }
                    }

                    // Endpoint de diagnóstico de salud
                    get("/status") {
                        val statusJson = JSONObject().apply {
                            put("status", "running")
                            put("activeClients", activeClients.size)
                            put("httpPort", port)
                            put("httpsPort", if (isTlsActive) sslPort else null)
                            put("tlsActive", isTlsActive)
                            put("timestamp", System.currentTimeMillis())
                        }
                        call.respondText(statusJson.toString(), ContentType.Application.Json)
                    }

                    // Endpoint compatible con el frontend de Taada (https://app.taada.top/)
                    get("/getsocketport") {
                        val resJson = JSONObject().apply {
                            put("port", sslPort)
                            put("resolution", 1) // 1 = 1280x720 (estándar vehicular)
                            put("width", 1280)
                            put("height", 720)
                            put("widthMargin", 0)
                            put("heightMargin", 0)
                            put("buildversion", 85) // Requerido >= 73 por app.taada.top para aceptar la app
                            put("aaHeadunitServerRequired", false)
                            put("usebt", true)
                            put("webcodec", true)
                        }
                        call.respondText(resJson.toString(), ContentType.Application.Json)
                    }

                    // Endpoint de diagnóstico interno detallado
                    get("/api/status") {
                        val status = onGetStatusRequest?.invoke() ?: JSONObject().apply {
                            put("status", "running")
                        }
                        call.respondText(status.toString(), ContentType.Application.Json)
                    }

                    // Endpoint para lanzar aplicaciones en la pantalla del vehículo directamente desde la web
                    get("/api/launch") {
                        val pkg = call.parameters["pkg"] ?: "com.google.android.apps.maps"
                        val success = safeLaunchApp(pkg)
                        val resJson = JSONObject().apply {
                            put("package", pkg)
                            put("success", success)
                        }
                        call.respondText(resJson.toString(), ContentType.Application.Json)
                    }

                    // Endpoint para simular teclas de sistema (ej. Back = 4, Home = 3)
                    get("/api/key") {
                        val code = call.parameters["code"]?.toIntOrNull() ?: 4
                        onKeyRequest?.invoke(code)
                        call.respondText(JSONObject().apply { put("key", code) }.toString(), ContentType.Application.Json)
                    }

                    // Endpoint para volver a la pantalla de reloj / dashboard
                    get("/api/home") {
                        onHomeRequest?.invoke()
                        call.respondText(JSONObject().apply { put("home", true) }.toString(), ContentType.Application.Json)
                    }

                    // Canal WebSocket bidireccional (soportando tanto / como /ws para Taada y cliente local)
                    webSocket("/") {
                        handleWebSocketSession(this)
                    }

                    webSocket("/ws") {
                        handleWebSocketSession(this)
                    }
                }
            }
        }

        serverEngine = embeddedServer(Netty, environment).start(wait = false)
        isRunning = true
        Log.i(TAG, "TeslaEmbeddedServer iniciado con éxito. HTTP:$port, HTTPS:${if (isTlsActive) sslPort else "desactivado"}")
    }

    fun stop() {
        if (!isRunning) return
        serverEngine?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
        serverEngine = null
        for (client in activeClients) {
            client.frameChannel.close()
        }
        activeClients.clear()
        isRunning = false
    }

    /**
     * Emite un frame de video NAL unit en formato binario a todos los clientes web conectados.
     * Utiliza un canal no bloqueante (DROP_OLDEST) por cliente para garantizar latencia cero
     * y prevenir cualquier condición de carrera de escritura en Netty / Ktor WebSockets.
     */
    fun broadcastVideoFrame(data: ByteArray) {
        if (!isRunning || activeClients.isEmpty()) return
        for (client in activeClients) {
            client.frameChannel.trySend(data)
        }
    }

    private suspend fun handleWebSocketSession(session: WebSocketSession) {
        val client = ConnectedClient(session)
        activeClients.add(client)

        // Corutina dedicada exclusivamente a enviar frames de video secuencialmente para este cliente
        val videoSenderJob = serverScope.launch {
            try {
                for (frameBytes in client.frameChannel) {
                    client.sendFrame(Frame.Binary(true, frameBytes))
                }
            } catch (ignored: Exception) {
            }
        }

        try {
            val infoJson = JSONObject().apply {
                put("type", "info")
                put("width", 1280)
                put("height", 720)
                put("codec", "h264")
            }
            client.sendFrame(Frame.Text(infoJson.toString()))

            getInitialConfigFrame?.invoke()?.let { spsPps ->
                client.sendFrame(Frame.Binary(true, spsPps))
            }

            onClientConnected?.invoke()

            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        handleIncomingTextPayload(text, client)
                    }
                    is Frame.Binary -> {
                        handleIncomingBinaryPayload(frame.readBytes())
                    }
                    else -> Unit
                }
            }
        } catch (ignored: Exception) {
        } finally {
            videoSenderJob.cancel()
            client.frameChannel.close()
            activeClients.remove(client)
        }
    }

    private fun handleIncomingBinaryPayload(bytes: ByteArray) {
        if (bytes.size < 8) return
        try {
            val actionCode = bytes[0].toInt() and 0xFF
            val touchesCount = bytes[1].toInt() and 0xFF
            if (touchesCount > 0 && bytes.size >= 8) {
                // Formato Big-Endian del BinaryTouchEncoder de Taada:
                // Byte 0: actionCode (0: DOWN, 1: MOVE, 2: UP, 3: TOUCH)
                // Byte 1: touchesCount
                // Bytes 2-3: id
                // Bytes 4-5: x (coordenada 0 a 1280)
                // Bytes 6-7: y (coordenada 0 a 720)
                val x = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
                val y = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
                val normX = (x.toDouble() / 1280.0).coerceIn(0.0, 1.0)
                val normY = (y.toDouble() / 720.0).coerceIn(0.0, 1.0)

                val type = when (actionCode) {
                    0 -> "pointer_down"
                    1 -> "pointer_move"
                    2 -> "pointer_up"
                    else -> "pointer_down"
                }

                onTouchEventReceived?.invoke(type, normX, normY, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error procesando touch binario: ${e.message}")
        }
    }

    private suspend fun handleIncomingTextPayload(
        text: String,
        client: ConnectedClient
    ) {
        try {
            val json = JSONObject(text)
            val action = json.optString("action")
            val type = json.optString("type")

            if (action == "START" || action == "REQUEST_KEYFRAME") {
                Log.d(TAG, "Cliente Taada web solicitó inicio de stream ($action)")
                onClientConnected?.invoke()
            } else if (action == "launch") {
                val pkg = json.optString("package")
                if (pkg.isNotEmpty()) {
                    safeLaunchApp(pkg)
                }
            } else if (action == "tap") {
                val x = json.optDouble("x", 0.0)
                val y = json.optDouble("y", 0.0)
                onTapRequest?.invoke(x, y)
            } else if (action == "swipe") {
                val x1 = json.optDouble("x1", 0.0)
                val y1 = json.optDouble("y1", 0.0)
                val x2 = json.optDouble("x2", 0.0)
                val y2 = json.optDouble("y2", 0.0)
                val dur = json.optInt("duration", 200)
                onSwipeRequest?.invoke(x1, y1, x2, y2, dur)
            } else if (action == "key") {
                val code = json.optInt("code", 4)
                onKeyRequest?.invoke(code)
            } else if (type == "ping") {
                val pongJson = JSONObject().apply {
                    put("type", "pong")
                    put("t", json.optLong("t"))
                }
                client.sendFrame(Frame.Text(pongJson.toString()))
            } else if (action == "touch") {
                val touchType = json.optString("type")
                val x = json.optDouble("x", 0.0)
                val y = json.optDouble("y", 0.0)
                val ts = json.optLong("timestamp", System.currentTimeMillis())
                onTouchEventReceived?.invoke(touchType, x, y, ts)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadAssetFile(path: String): ByteArray? {
        return try {
            context.assets.open(path).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }
}
