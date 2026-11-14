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
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.KeyStore
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

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
    private val activeSessions = ConcurrentHashMap.newKeySet<WebSocketSession>()
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

    private fun loadKeyStore(): KeyStore? {
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            context.assets.open("keystore/tesaauto.p12").use { input ->
                ks.load(input, "tesaautopassword".toCharArray())
            }
            Log.i(TAG, "Keystore TLS PKCS12 cargado con éxito para HTTPS")
            ks
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cargar keystore TLS: ${e.message}. El servidor solo operará en HTTP.")
            null
        }
    }

    fun start() {
        if (isRunning) return

        val keyStore = loadKeyStore()

        val environment = applicationEngineEnvironment {
            connector {
                this.port = this@TeslaEmbeddedServer.port
                this.host = this@TeslaEmbeddedServer.host
            }

            if (keyStore != null) {
                try {
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = "tesaauto",
                        keyStorePassword = { "tesaautopassword".toCharArray() },
                        privateKeyPassword = { "tesaautopassword".toCharArray() }
                    ) {
                        this.port = this@TeslaEmbeddedServer.sslPort
                        this.host = this@TeslaEmbeddedServer.host
                    }
                    isTlsActive = true
                    Log.i(TAG, "Conector SSL HTTPS habilitado con éxito en puerto ${this@TeslaEmbeddedServer.sslPort}")
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
                            put("activeClients", activeSessions.size)
                            put("httpPort", port)
                            put("httpsPort", if (isTlsActive) sslPort else null)
                            put("tlsActive", isTlsActive)
                            put("timestamp", System.currentTimeMillis())
                        }
                        call.respondText(statusJson.toString(), ContentType.Application.Json)
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

                    // Canal WebSocket bidireccional
                    webSocket("/ws") {
                        activeSessions.add(this)

                        try {
                            // Enviar información inicial de display al cliente
                            val infoJson = JSONObject().apply {
                                put("type", "info")
                                put("width", 1280)
                                put("height", 720)
                                put("codec", "h264")
                            }
                            send(Frame.Text(infoJson.toString()))

                            // Enviar SPS/PPS inicial de inmediato si ya está disponible en caché para inicializar decodificador
                            getInitialConfigFrame?.invoke()?.let { spsPps ->
                                send(Frame.Binary(true, spsPps))
                            }

                            onClientConnected?.invoke()

                            // Escuchar mensajes entrantes (eventos táctiles y pings)
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        handleIncomingTextPayload(text, this)
                                    }
                                    is Frame.Binary -> {
                                        // Payloads binarios del cliente si aplica
                                    }
                                    else -> Unit
                                }
                            }
                        } catch (e: Exception) {
                            // Desconexión o error de socket
                        } finally {
                            activeSessions.remove(this)
                        }
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
        activeSessions.clear()
        isRunning = false
    }

    /**
     * Emite un frame de video NAL unit en formato binario a todos los clientes web conectados.
     */
    fun broadcastVideoFrame(data: ByteArray) {
        if (!isRunning || activeSessions.isEmpty()) return

        serverScope.launch {
            val frame = Frame.Binary(true, data)
            for (session in activeSessions) {
                try {
                    session.send(frame)
                } catch (e: ClosedSendChannelException) {
                    activeSessions.remove(session)
                } catch (e: Exception) {
                    activeSessions.remove(session)
                }
            }
        }
    }

    private suspend fun handleIncomingTextPayload(
        text: String,
        session: WebSocketSession
    ) {
        try {
            val json = JSONObject(text)
            val action = json.optString("action")
            val type = json.optString("type")

            if (action == "launch") {
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
                session.send(Frame.Text(pongJson.toString()))
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
