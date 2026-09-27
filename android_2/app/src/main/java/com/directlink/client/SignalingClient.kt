package com.directlink.client

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SignalingClient(private val signalingUrl: String) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    
    // Text signaling events
    private val _events = Channel<JSONObject>(Channel.UNLIMITED)
    val events = _events.receiveAsFlow()

    // Binary video frame relay
    private val _binaryFrames = Channel<ByteArray>(Channel.UNLIMITED)
    val binaryFrames = _binaryFrames.receiveAsFlow()

    fun connect(remoteId: String, onConnected: () -> Unit, onError: (String) -> Unit) {
        val request = try {
            Request.Builder().url(signalingUrl).build()
        } catch (e: Exception) {
            onError("Invalid URL: ${e.message}")
            return
        }
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("Signaling", "Connected to signaling server")
                // Request connection to the Host
                val msg = JSONObject().apply {
                    put("type", "CLIENT_CONNECT")
                    put("remoteId", remoteId)
                }
                webSocket.send(msg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("Signaling", "Received: $text")
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "ERROR" -> {
                            val msg = json.optString("message", "Unknown error")
                            onError(msg)
                            webSocket.close(1000, "Error")
                        }
                        else -> {
                            _events.trySend(json)
                            if (json.optString("type") == "CLIENT_REQUEST_ACK" || json.optString("type") == "HOST_INFO") {
                                onConnected()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Signaling", "Parse error", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Binary message = H.264 video chunk from host
                _binaryFrames.trySend(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Signaling", "WebSocket Error", t)
                onError(t.message ?: "Connection failed")
            }
        })
    }
    
    fun sendBinary(payload: ByteArray) {
        val bs = okio.ByteString.of(*payload)
        webSocket?.send(bs)
    }

    fun sendCandidate(sessionId: String, candidate: String) {
        val msg = JSONObject().apply {
            put("type", "CANDIDATE")
            put("sessionId", sessionId)
            put("candidate", candidate)
            put("isHost", false)
        }
        webSocket?.send(msg.toString())
    }
    
    fun requestRelay(sessionId: String) {
        val msg = JSONObject().apply {
            put("type", "ALLOCATE_RELAY")
            put("sessionId", sessionId)
        }
        webSocket?.send(msg.toString())
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
